package com.deepx.apicenter;

import com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.CredentialRow;
import com.deepx.apicenter.repository.ClientAppRepository;
import com.deepx.apicenter.repository.CredentialOwner;
import com.deepx.apicenter.repository.CredentialRepository;
import com.deepx.apicenter.service.ClientService;
import com.deepx.apicenter.service.InboundCredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入站鉴权 **v1.2 凭证池 C1** 集成测试（2026-09-24，《入站鉴权设计方案》v1.2 §19.2）。
 *
 * <p>覆盖 C1 出口标志：**三级属主可 CRUD** / **v1.1 存量凭证迁移后仍可读** / **`label` 可写可遮显回显**，
 * 以及 v1.2 的两条核心语义：
 * <ol>
 *   <li><b>池隔离</b>：同 kind 下，接口池 / 平台池 / 档案池三者互不可见（`owner_type` 参与过滤）；
 *       这是「接口专属池」成立的前提（设计方案 §6.1 ⭐ 逐级短路）。</li>
 *   <li><b>单独吊销</b>：平台池两把密钥，吊销其一不影响另一把 —— 用户诉求「需要只吊销某一个调用方」的直接落地。</li>
 * </ol>
 *
 * <p>⚠️ 闸门内的判定（以凭证为中心、逐级短路、`require_client_id` 两档）属 **C2**，不在本类。
 *
 * <p>注意：四个 worker 隔离属性照 CLAUDE.md 硬要求全置大（漏一个就会在全量套件下偶发红）。
 */
@SpringBootTest(properties = {
        "app.api-center.retry-worker-fixed-delay-ms=3600000",
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-initial-delay-ms=3600000",
        "app.api-center.alert-worker-initial-delay-ms=3600000"
})
class InboundCredentialPoolIntegrationTest {

    private static final String CLIENT = "TEST-POOL-CLIENT";

    @Autowired
    private InboundCredentialService poolService;
    @Autowired
    private ClientService clientService;
    @Autowired
    private ClientAppRepository clientAppRepository;
    @Autowired
    private CredentialRepository credentialRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 本类创建的平台/接口池凭证 id（**测试隔离**：清理只删自己建的，不整池清空——
     * 整池清空会误删人工验收或其他用例/历史遗留的凭证，2026-09-24 代码评审发现）。
     */
    private final java.util.List<Long> createdCredentialIds = new java.util.ArrayList<>();

    /**
     * 建平台池凭证并登记 id（供 cleanup 精确删除）。
     *
     * <p>⚠️ 平台池是**全局共享**状态：可能有人工验收/联调留下的"待激活轮换行"（同一 kind 只允许一把待激活）⇒
     * 这里**自动挑一个当前没有待激活轮换的 kind**，既不撞守卫，也**绝不动别人的数据**。
     */
    private CredentialIssuedView newPlatformCredential(String label) {
        return newPlatformCredential(freePlatformKind(), label);
    }

    private CredentialIssuedView newPlatformCredential(String kind, String label) {
        CredentialIssuedView issued = poolService.prepare("PLATFORM", null, kind, label);
        createdCredentialIds.add(issued.id());
        return issued;
    }

    /**
     * 平台池列表**只看本类自己造的**（共享池里可能有别人的行 ⇒ 不能假设"池里只有我这一条"）。
     * @param label 精确匹配的备注（本类标签一律带 `单测·` 前缀）
     */
    private List<CredentialView> ownPlatformViews(String label) {
        return poolService.list("PLATFORM", null).stream()
                .filter(v -> label.equals(v.label()))
                .toList();
    }

    /** 平台池里"当前没有待激活（live ROTATING）"的 kind —— 与 prepare 的守卫同口径 */
    private String freePlatformKind() {
        for (String kind : List.of("API_KEY", "HMAC_SECRET", "BEARER_TOKEN", "BASIC")) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM client_credential WHERE owner_type = 'PLATFORM' AND kind = ? "
                            + "AND status = 'ROTATING' AND (rotating_until IS NULL OR rotating_until > NOW())",
                    Integer.class, kind);
            if (n == null || n == 0) {
                return kind;
            }
        }
        throw new IllegalStateException("平台池四种 kind 都有待激活轮换行（人工验收残留？）");
    }

    /**
     * **自愈式清理**（2026-09-24）：本类造数一律带 `单测·` 标签前缀 ⇒ 按前缀清，
     * 既能清掉"上一次跑崩留下的轮换行"（否则会撞 `已有待激活的轮换凭证` 守卫），
     * 又**绝不触碰**人工验收/演示发放的凭证（它们不带该前缀）。
     */
    @org.junit.jupiter.api.BeforeEach
    void cleanOwnDataBefore() {
        cleanupOwnData();
    }

    /** 清理本类造数：CLIENT 属主走 clientService 级联删；INTERFACE / PLATFORM 池按属主删 */
    @AfterEach
    void cleanup() {
        if (clientAppRepository.existsById(CLIENT)) {
            clientService.delete(CLIENT);
        }
        cleanupOwnData();
    }

    /** 清掉本类造的所有凭证：按登记的 id + 按 `单测·` 标签前缀（前缀兜底"上一轮跑崩的残留"） */
    private void cleanupOwnData() {
        for (Long id : createdCredentialIds) {
            jdbcTemplate.update("DELETE FROM client_credential WHERE id = ?", id);
        }
        createdCredentialIds.clear();
        jdbcTemplate.update("DELETE FROM client_credential WHERE label LIKE '单测·%'");
        jdbcTemplate.update("DELETE FROM client_credential WHERE owner_type = 'INTERFACE' "
                + "AND owner_id IN (SELECT CAST(id AS CHAR) FROM interface WHERE code = 'TEST-POOL-IF')");
        jdbcTemplate.update("DELETE FROM interface WHERE code = 'TEST-POOL-IF'");
    }

    // ---------- 平台池（L1 默认路径：不登记调用方也能接入） ----------

    @Test
    void 平台池_新增带备注_列表回显备注与指纹_不回明文() {
        CredentialIssuedView issued = newPlatformCredential("单测·某公司 2026-09-24");
        assertThat(issued.plaintext()).isNotBlank();

        List<CredentialView> views = ownPlatformViews("单测·某公司 2026-09-24");
        assertThat(views).hasSize(1);
        assertThat(views.get(0).label()).isEqualTo("单测·某公司 2026-09-24");
        assertThat(views.get(0).fingerprint()).isEqualTo(issued.plaintext().substring(issued.plaintext().length() - 4));
        // 库里是密文（管理面永不回显明文这条纪律的库级证据）
        assertThat(credentialRepository.countByCredentialText(CredentialOwner.PLATFORM, null, issued.plaintext()))
                .isZero();
    }

    @Test
    void 平台池_不接受ownerId_且属主类型非法与越界一律拒绝() {
        assertThatThrownBy(() -> poolService.prepare("PLATFORM", "X", "API_KEY", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不接受 ownerId");
        // 应用（供应商）凭证不属入站凭证池
        assertThatThrownBy(() -> poolService.list("APP", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于入站凭证池");
        assertThatThrownBy(() -> poolService.list("NOPE", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法属主类型");
        assertThatThrownBy(() -> poolService.prepare("PLATFORM", null, "OUTBOUND", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("凭证类型仅支持");
        // 备注超长：宁可拒绝，不静默截断（用空闲 kind，避免先撞上"已有待激活轮换"的守卫）
        String freeKind = freePlatformKind();
        assertThatThrownBy(() -> poolService.prepare("PLATFORM", null, freeKind, "x".repeat(65)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("备注最长 64 字符");
    }

    @Test
    void 平台池_单独吊销一把密钥_另一把仍有效() {
        CredentialIssuedView a = newPlatformCredential("单测·A 公司");
        // prepare 生成 ROTATING 待激活；先激活再发下一把（同 kind 只允许一把待激活 —— M0-04 轮换语义）
        poolService.activate("PLATFORM", null, a.id());
        CredentialIssuedView b = newPlatformCredential("单测·B 公司");
        poolService.activate("PLATFORM", null, b.id());

        poolService.retire("PLATFORM", null, a.id());

        List<CredentialView> views = poolService.list("PLATFORM", null);
        assertThat(views).filteredOn(v -> v.id() == a.id()).singleElement()
                .satisfies(v -> assertThat(v.status()).isEqualTo("RETIRED"));
        assertThat(views).filteredOn(v -> v.id() == b.id()).singleElement()
                .satisfies(v -> assertThat(v.status()).isEqualTo("ACTIVE"));
    }

    @Test
    void 平台池_仅改备注_不改变状态与凭证值() {
        CredentialIssuedView issued = newPlatformCredential("单测·旧备注");
        poolService.updateLabel("PLATFORM", null, issued.id(), "单测·补充：某公司生产环境");

        CredentialView view = ownPlatformViews("单测·补充：某公司生产环境").stream().findFirst()
                .orElseThrow(() -> new AssertionError("未找到本用例的那一行（平台池是共享的，必须按标签过滤）"));
        assertThat(view.label()).isEqualTo("单测·补充：某公司生产环境");
        assertThat(view.status()).isEqualTo("ROTATING");
        assertThat(view.fingerprint()).isEqualTo(issued.plaintext().substring(issued.plaintext().length() - 4));
    }

    // ---------- 接口池（接口隔离的前提） ----------

    @Test
    void 接口池与平台池互不可见_同kind也不串() {
        long interfaceId = newFixtureInterface();
        String kind = freePlatformKind();          // 平台池可能有人工验收残留 ⇒ 自动挑空闲 kind
        newPlatformCredential(kind, "单测·平台共享");
        poolService.prepare("INTERFACE", String.valueOf(interfaceId), kind, "仅本接口");

        List<CredentialView> platform = poolService.list("PLATFORM", null);
        List<CredentialView> iface = poolService.list("INTERFACE", String.valueOf(interfaceId));

        assertThat(platform).extracting(CredentialView::label).contains("单测·平台共享");
        assertThat(iface).extracting(CredentialView::label).containsExactly("仅本接口");
        // 仓储层同结论（闸门 C2 直接依赖它做逐级短路）：**同一个 kind** 下两级互不可见
        assertThat(credentialRepository.findVerifiable(CredentialOwner.PLATFORM, null, kind))
                .extracting(CredentialRow::label).contains("单测·平台共享");
        assertThat(credentialRepository.findVerifiable(CredentialOwner.INTERFACE, String.valueOf(interfaceId),
                kind)).extracting(CredentialRow::label).containsExactly("仅本接口");
    }

    @Test
    void 接口池_ownerId必须是真实存在的接口数字id() {
        assertThatThrownBy(() -> poolService.prepare("INTERFACE", "not-a-number", "API_KEY", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须是接口数字 id");
        assertThatThrownBy(() -> poolService.prepare("INTERFACE", "99999999", "API_KEY", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("接口不存在");
    }

    // ---------- 档案池（L2 可选精确管控）与迁移不变量 ----------

    @Test
    void 档案池_需真实调用方_且与ClientCredentialService口径一致() {
        clientService.create(new com.deepx.apicenter.dto.ClientDtos.ClientRequest(
                CLIENT, "凭证池测试调用方", null, null, null, null, null, null, null));
        poolService.prepare("CLIENT", CLIENT, "API_KEY", "单测·登记调用方的密钥");

        assertThat(poolService.list("CLIENT", CLIENT)).hasSize(1);
        assertThat(credentialRepository.findVerifiable(CredentialOwner.CLIENT, CLIENT, "API_KEY")).hasSize(1);

        assertThatThrownBy(() -> poolService.prepare("CLIENT", "NO-SUCH-CLIENT", "API_KEY", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("调用方不存在");
    }

    @Test
    void 迁移不变量_所有池行的属主列自洽_平台池必须owner_id为空() {
        // v1.1 → v1.2 迁移（加 owner_type/owner_id/label 并回填）后，历史行必须落在合法取值上；
        // 断言「不变量」而非具体行数 —— 避免写死 seed 库态（2026-09-18 同类事故）
        Integer illegal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_credential WHERE owner_type IS NULL "
                        + "OR owner_type NOT IN ('CLIENT', 'INTERFACE', 'PLATFORM')", Integer.class);
        assertThat(illegal).isZero();
        Integer platformWithId = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_credential WHERE owner_type = 'PLATFORM' AND owner_id IS NOT NULL",
                Integer.class);
        assertThat(platformWithId).isZero();
        Integer othersWithoutId = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_credential WHERE owner_type <> 'PLATFORM' AND owner_id IS NULL",
                Integer.class);
        assertThat(othersWithoutId).isZero();
    }

    @Test
    void 删调用方_级联删其池凭证_不留孤儿_v1_2回归() {
        // 回归：v1.2 把凭证归入凭证池后，属主改由 (owner_type, owner_id) 表达；
        // 若级联删仍按旧列 client_id（新行该列为 NULL）⇒ 凭证会静默残留（本类清理钩子就是先被它咬到）
        clientService.create(new com.deepx.apicenter.dto.ClientDtos.ClientRequest(
                CLIENT, "级联删回归", null, null, null, null, null, null, null));
        poolService.prepare("CLIENT", CLIENT, "API_KEY", "单测·登记调用方的密钥");
        assertThat(credentialRepository.findByOwner(CredentialOwner.CLIENT, CLIENT)).isNotEmpty();

        clientService.delete(CLIENT);

        assertThat(credentialRepository.findByOwner(CredentialOwner.CLIENT, CLIENT)).isEmpty();
    }

    /**
     * 凭证池列表要能回答「这是**哪个接口**的凭证」（2026-09-24 使用反馈）：
     * 列表项必须带 `ownerType` / `ownerId`（接口 = 数字 id）/ `ownerName`（接口名称）；
     * 平台池的 ownerName 是固定文案、ownerId 为空。
     */
    @Test
    void 凭证池列表带属主信息_接口池显示接口id与接口名称() {
        long interfaceId = newFixtureInterface();
        poolService.prepare("INTERFACE", String.valueOf(interfaceId), "API_KEY", "单测·接口池属主信息");

        CredentialView ifaceView = poolService.list("INTERFACE", String.valueOf(interfaceId)).stream()
                .filter(v -> "单测·接口池属主信息".equals(v.label())).findFirst().orElseThrow();
        assertThat(ifaceView.ownerType()).isEqualTo("INTERFACE");
        assertThat(ifaceView.ownerId()).isEqualTo(String.valueOf(interfaceId));      // ★ 接口 ID
        assertThat(ifaceView.ownerName()).isEqualTo("凭证池夹具接口");                   // ★ 接口名称

        newPlatformCredential("单测·平台池属主信息");
        CredentialView platformView = ownPlatformViews("单测·平台池属主信息").stream()
                .findFirst().orElseThrow();
        assertThat(platformView.ownerType()).isEqualTo("PLATFORM");
        assertThat(platformView.ownerName()).isEqualTo("平台共享池");                    // 固定文案
        assertThat(platformView.ownerId()).isNull();                                  // 平台池无 ownerId
    }

    // ---------- 夹具 ----------

    /** 建一个最小可用的出站中转接口（接口池需要真实 interface 行；不动 seed 资产） */
    private long newFixtureInterface() {
        jdbcTemplate.update("INSERT INTO interface (code, name, if_type, method, path, protocol_in, protocol_out, "
                        + "app_id, group_id, status) "
                        + "SELECT 'TEST-POOL-IF', '凭证池夹具接口', 'OUTBOUND', 'POST', '/test-pool-fixture', "
                        + "'JSON', 'JSON', a.app_id, g.id, 'PUBLISHED' "
                        + "FROM app a JOIN app_group g ON g.app_id = a.app_id LIMIT 1");
        return jdbcTemplate.queryForObject("SELECT id FROM interface WHERE code = 'TEST-POOL-IF'", Long.class);
    }
}
