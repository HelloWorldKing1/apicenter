package com.deepx.apicenter;

import com.deepx.apicenter.dto.ClientDtos.ClientRequest;
import com.deepx.apicenter.dto.ClientDtos.ClientResponse;
import com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.dto.CredentialDtos.PrepareRequest;
import com.deepx.apicenter.dto.CredentialDtos.ResetRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.repository.ClientAppRepository;
import com.deepx.apicenter.repository.CredentialOwner;
import com.deepx.apicenter.repository.CredentialRepository;
import com.deepx.apicenter.service.ClientCredentialService;
import com.deepx.apicenter.service.ClientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台入站鉴权 **B1「数据与目录」** 集成测试（2026-09-23，《入站鉴权设计方案》v1.1 §19）。
 *
 * <p>覆盖 B1 出口标志：**调用方可 CRUD** / **可建与轮换凭证** / **凭证遮显不回明文** / **`client_app` 停用生效**；
 * 另验证「凭证机制抽取（{@code CredentialStore}）后口径与 M0-04 **完全一致**」——即调用方凭证与应用凭证
 * 共用同一套状态机语义（`ACTIVE`/`ROTATING`/`RETIRED`、只统计未过期 ROTATING、仅 RETIRED 可删、CAS 流转）。
 *
 * <p>⚠️ 鉴权**判定**（闸门）属 B2/B3，不在本类。
 *
 * <p>注意：@SpringBootTest 启动会触发 SeedDataInitializer（幂等）；四个 worker 隔离属性照 CLAUDE.md 硬要求全置大，
 * 否则上下文启动即跑一轮全局扫描，与用例抢跑（历史上偶发红的根因）。
 */
@SpringBootTest(properties = {
        "app.api-center.retry-worker-fixed-delay-ms=3600000",
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-initial-delay-ms=3600000",
        "app.api-center.alert-worker-initial-delay-ms=3600000"
})
class ClientAuthIntegrationTest {

    private static final String CLIENT = "TEST-CLIENT-B1";
    private static final String OTHER = "TEST-CLIENT-B1X";

    @Autowired
    private ClientService clientService;
    @Autowired
    private ClientCredentialService clientCredentialService;
    @Autowired
    private ClientAppRepository clientAppRepository;
    @Autowired
    private CredentialRepository credentialRepository;

    /** 清理本类造数（客户端 + 级联凭证）；不写死断言，避免与其他用例/种子冲突 */
    @AfterEach
    void cleanup() {
        for (String id : List.of(CLIENT, OTHER)) {
            if (clientAppRepository.existsById(id)) {
                clientAppRepository.deleteCascade(id);
            }
        }
    }

    private ClientRequest req(String clientId, String name, String adapterId) {
        return new ClientRequest(clientId, name, "联系人", adapterId, null, null, null, null, "B1 集成测试");
    }

    // ---------- CRUD ----------

    @Test
    void 调用方CRUD_标识校验与适配器校验() {
        ClientResponse created = clientService.create(req(CLIENT, "B1 测试调用方", null));
        assertThat(created.clientId()).isEqualTo(CLIENT);
        assertThat(created.status()).isEqualTo("ENABLED");
        assertThat(created.activeCredentialKinds()).isEmpty();

        // 列表能查到（keyword 过滤）
        assertThat(clientService.list("TEST-CLIENT-B1", null))
                .extracting(ClientResponse::clientId).contains(CLIENT);

        // 更新（标识不可改；换名 + 绑鉴权适配器）
        ClientResponse updated = clientService.update(CLIENT, req(CLIENT, "B1 改名", "ADP-000"));
        assertThat(updated.name()).isEqualTo("B1 改名");
        assertThat(updated.authAdapterId()).isEqualTo("ADP-000");

        // 标识格式非法（小写 / 太短 / 特殊字符）
        assertThatThrownBy(() -> clientService.create(req("bad-lower", "x", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("仅允许 3~32 位大写");
        assertThatThrownBy(() -> clientService.create(req("AB", "x", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("仅允许 3~32 位大写");

        // 标识重复
        assertThatThrownBy(() -> clientService.create(req(CLIENT, "重复", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("已存在");

        // 适配器不存在
        assertThatThrownBy(() -> clientService.create(req(OTHER, "x", "ADP-NOT-EXIST")))
                .isInstanceOf(BizException.class).hasMessageContaining("鉴权适配器不存在");
    }

    @Test
    void 停用与启用生效() {
        clientService.create(req(CLIENT, "B1 启停", null));
        clientService.setStatus(CLIENT, "DISABLED");
        assertThat(clientAppRepository.isEnabled(CLIENT)).isFalse();
        assertThat(clientService.detail(CLIENT).client().status()).isEqualTo("DISABLED");

        clientService.setStatus(CLIENT, "ENABLED");
        assertThat(clientAppRepository.isEnabled(CLIENT)).isTrue();
    }

    @Test
    void 删除级联删凭证() {
        clientService.create(req(CLIENT, "B1 删除", null));
        clientCredentialService.update(CLIENT, new UpdateRequest("API_KEY", "delete-me-1234"));
        assertThat(credentialRepository.findByOwner(CredentialOwner.CLIENT, CLIENT)).isNotEmpty();

        clientService.delete(CLIENT);
        assertThat(clientAppRepository.existsById(CLIENT)).isFalse();
        assertThat(credentialRepository.findByOwner(CredentialOwner.CLIENT, CLIENT)).isEmpty();
    }

    // ---------- 凭证：与 M0-04 口径一致（抽取 CredentialStore 后的回归） ----------

    @Test
    void 调用方凭证_加密落库_遮显与轮换流转() {
        clientService.create(req(CLIENT, "B1 凭证", null));

        // 更新：ACTIVE + 加密落库（库中无明文）
        clientCredentialService.update(CLIENT, new UpdateRequest("API_KEY", "caller-secret-1234"));
        assertThat(credentialRepository.countByCredentialText(CredentialOwner.CLIENT, CLIENT, "caller-secret-1234"))
                .isZero();

        List<CredentialView> views = clientCredentialService.listViews(CLIENT);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).fingerprint()).isEqualTo("1234");   // 只回尾 4 位
        assertThat(views.get(0).status()).isEqualTo("ACTIVE");

        // 再更新：旧 ACTIVE → ROTATING（并存 24h），新 → ACTIVE
        clientCredentialService.update(CLIENT, new UpdateRequest("API_KEY", "caller-secret-5678"));
        views = clientCredentialService.listViews(CLIENT);
        assertThat(views).extracting(CredentialView::status).containsExactlyInAnyOrder("ACTIVE", "ROTATING");

        // 重置（应急）：旧全部 RETIRED，新 ACTIVE 唯一
        clientCredentialService.reset(CLIENT, new ResetRequest("API_KEY", "caller-secret-90ab"));
        views = clientCredentialService.listViews(CLIENT);
        assertThat(views.stream().filter(v -> "ACTIVE".equals(v.status()))).hasSize(1);
        assertThat(views.stream().filter(v -> "ACTIVE".equals(v.status())).findFirst().orElseThrow().fingerprint())
                .isEqualTo("90ab");

        // 失效最后一个 ACTIVE → 告警文案为「调用方」口径（与应用侧措辞不同）
        long activeId = views.stream().filter(v -> "ACTIVE".equals(v.status())).findFirst().orElseThrow().id();
        assertThat(clientCredentialService.retire(CLIENT, activeId)).contains("调用方入站鉴权");
    }

    @Test
    void 调用方凭证_类型白名单按属主区分() {
        clientService.create(req(CLIENT, "B1 白名单", null));
        // 调用方凭证不支持应用的 OUTBOUND/CALLBACK 类型
        assertThatThrownBy(() -> clientCredentialService.update(CLIENT, new UpdateRequest("OUTBOUND", "x")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("API_KEY / HMAC_SECRET / BEARER_TOKEN / BASIC");
    }

    @Test
    void prepare_明文仅回显一次且库中为密文_未激活时不可重复prepare() {
        clientService.create(req(CLIENT, "B1 轮换", null));

        CredentialIssuedView issued = clientCredentialService.prepare(CLIENT, new PrepareRequest("HMAC_SECRET"));
        assertThat(issued.plaintext()).hasSize(64);                       // 32 字节 hex，仅本次回显
        assertThat(issued.kind()).isEqualTo("HMAC_SECRET");
        assertThat(credentialRepository.countByCredentialText(CredentialOwner.CLIENT, CLIENT, issued.plaintext()))
                .isZero();                                                // 落库是密文
        List<CredentialView> views = clientCredentialService.listViews(CLIENT);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).status()).isEqualTo("ROTATING");
        assertThat(views.get(0).fingerprint()).isEqualTo(issued.plaintext().substring(60)); // 尾 4

        // 已有待激活的轮换凭证 → 拒绝再次 prepare（E2 口径：只统计未过期 ROTATING）
        assertThatThrownBy(() -> clientCredentialService.prepare(CLIENT, new PrepareRequest("HMAC_SECRET")))
                .isInstanceOf(BizException.class).hasMessageContaining("已有待激活的轮换凭证");

        // 激活：ROTATING → ACTIVE
        clientCredentialService.activate(CLIENT, issued.id());
        assertThat(clientCredentialService.listViews(CLIENT))
                .extracting(CredentialView::status).containsExactly("ACTIVE");
    }

    @Test
    void 轮换收尾与删除保护_仅RETIRED可删() {
        clientService.create(req(CLIENT, "B1 收尾", null));
        CredentialIssuedView issued = clientCredentialService.prepare(CLIENT, new PrepareRequest("BEARER_TOKEN"));
        clientCredentialService.activate(CLIENT, issued.id());

        // ACTIVE 不可删（状态机保护）
        assertThatThrownBy(() -> clientCredentialService.delete(CLIENT, issued.id()))
                .isInstanceOf(BizException.class).hasMessageContaining("仅已失效（RETIRED）凭证可删除");

        // 收尾轮换：ROTATING → RETIRED 才可删；此处先 prepare 出 ROTATING 再收尾
        CredentialIssuedView rotating = clientCredentialService.prepare(CLIENT, new PrepareRequest("BASIC"));
        clientCredentialService.finishRotation(CLIENT, rotating.id());
        assertThat(clientCredentialService.listViews(CLIENT).stream()
                .filter(v -> v.id() == rotating.id()).findFirst().orElseThrow().status())
                .isEqualTo("RETIRED");
        clientCredentialService.delete(CLIENT, rotating.id());
        assertThat(clientCredentialService.listViews(CLIENT)).noneMatch(v -> v.id() == rotating.id());
    }

    @Test
    void 凭证归属校验_不能跨调用方操作() {
        clientService.create(req(CLIENT, "A", null));
        clientService.create(req(OTHER, "B", null));
        CredentialIssuedView issued = clientCredentialService.prepare(CLIENT, new PrepareRequest("API_KEY"));

        assertThatThrownBy(() -> clientCredentialService.activate(OTHER, issued.id()))
                .isInstanceOf(BizException.class).hasMessageContaining("凭证不属于该调用方");
        assertThatThrownBy(() -> clientCredentialService.listViews("NOT-EXIST-CLIENT"))
                .isInstanceOf(BizException.class).hasMessageContaining("调用方不存在");
    }

    @Test
    void 列表凭证角标_按ACTIVE存在性() {
        clientService.create(req(CLIENT, "B1 角标", null));
        assertThat(clientService.list(CLIENT, null).get(0).activeCredentialKinds()).isEmpty();

        CredentialIssuedView issued = clientCredentialService.prepare(CLIENT, new PrepareRequest("API_KEY"));
        clientCredentialService.activate(CLIENT, issued.id());

        assertThat(clientService.list(CLIENT, null).get(0).activeCredentialKinds()).containsExactly("API_KEY");
        assertThat(clientService.detail(CLIENT).client().activeCredentialKinds()).containsExactly("API_KEY");
        assertThat(clientService.detail(CLIENT).credentials()).hasSize(1);
    }
}
