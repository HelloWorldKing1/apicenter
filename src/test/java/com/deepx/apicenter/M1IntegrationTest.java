package com.deepx.apicenter;

import com.deepx.apicenter.dto.AdapterDtos.AdapterRequest;
import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.AppDtos.AppResponse;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.dto.CredentialDtos.ResetRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.FieldDefDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceResponse;
import com.deepx.apicenter.dto.InterfaceDtos.ParamDto;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.CredentialRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.service.AdapterService;
import com.deepx.apicenter.service.AppService;
import com.deepx.apicenter.service.CredentialService;
import com.deepx.apicenter.service.GroupService;
import com.deepx.apicenter.service.InterfaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 集成测试：直连开发 PolarDB（M1 评审确认点 2），测试数据自清理。
 * 覆盖开发计划 M1 测试点：DDL 落库（16 表）、配置 CRUD 全链路、黄金用例回读、
 * 生命周期状态机、类型互斥、凭证加密/遮显/轮换、分组归属与删除规则。
 * 注意：@SpringBootTest 启动会触发 SeedDataInitializer（幂等，库中无 fastmoss 时导入）。
 */
@SpringBootTest
class M1IntegrationTest {

    private static final String TEST_APP = "TEST-M1-APP";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AppService appService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private InterfaceService interfaceService;
    @Autowired
    private CredentialService credentialService;
    @Autowired
    private CredentialRepository credentialRepository;
    @Autowired
    private AppRepository appRepository;
    @Autowired
    private InterfaceRepository interfaceRepository;
    @Autowired
    private AdapterService adapterService;

    /** 清理本测试类产生的数据（接口及子表 + 应用；seed 的 fastmoss 数据不动） */
    @AfterEach
    void cleanup() {
        if (appRepository.existsById(TEST_APP)) {
            jdbcTemplate.queryForList("SELECT id FROM interface WHERE app_id = ?", Long.class, TEST_APP)
                    .forEach(interfaceRepository::deleteCascade);
            appRepository.deleteCascade(TEST_APP);
        }
    }

    // ---------- DDL 落库 ----------

    @Test
    void 十六张表已落库() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'apicenter'", Integer.class);
        assertThat(n).isEqualTo(16);
    }

    // ---------- 黄金用例回读（开发计划 M2 出口基准的配置部分） ----------

    @Test
    void 黄金用例配置可回读校验() {
        // 应用（seed 导入或此前已存在）
        AppResponse app = appService.detail("fastmoss");
        assertThat(app.appId()).isEqualTo("fastmoss");
        assertThat(app.status()).isEqualTo("ENABLED");
        assertThat(app.baseUrl()).isEqualTo("https://openapi.fastmoss.com");
        assertThat(app.authAdapterId()).isEqualTo("ADP-101");

        // 凭证遮显：仅指纹、不回显明文。
        // 指纹随凭证值变化（管理员可更新凭证，初始 fastmoss-test-token 尾 4 位为 oken），
        // 断言遮显语义（非空指纹、不回显明文）而非锁死具体值。
        // M3 种子新增 CALLBACK 回调验签凭证——此处按 kind=OUTBOUND 过滤断言（黄金用例出站凭证）。
        List<CredentialView> creds = app.credentials().stream()
                .filter(c -> "OUTBOUND".equals(c.kind()))
                .toList();
        assertThat(creds).hasSize(1);
        CredentialView cred = creds.get(0);
        assertThat(cred.status()).isEqualTo("ACTIVE");
        assertThat(cred.fingerprint()).isNotBlank();

        // 接口定义（seed 导入或此前已存在；按 code 定位后取 detail——列表不带子表）
        InterfaceResponse iface = interfaceService.detail(interfaceService.list("fastmoss", null).stream()
                .filter(i -> "IF-FM-001".equals(i.code()))
                .findFirst()
                .orElseThrow()
                .id());
        assertThat(iface.code()).isEqualTo("IF-FM-001");
        assertThat(iface.ifType()).isEqualTo("OUTBOUND");
        assertThat(iface.status()).isEqualTo("PUBLISHED");
        assertThat(iface.upstreamPath()).isEqualTo("/shop/v1/creatorList");
        assertThat(iface.params()).hasSize(8); // IN 4 + OUT 4（透传）
        assertThat(iface.mappings()).isEmpty(); // 空映射 = 整体透传
        assertThat(iface.fieldDefs()).extracting(InterfaceRow.FieldDefRow::name)
                .containsExactlyInAnyOrder("total", "list");
        assertThat(iface.bindings()).extracting(InterfaceRow.BindingRow::role)
                .containsExactlyInAnyOrder("AUTH", "MESSAGE");
    }

    // ---------- 应用生命周期状态机 ----------

    @Test
    void 应用生命周期流转与停用即拒() {
        appService.create(new AppRequest(TEST_APP, "测试应用", null, null, null, null,
                null, null, null, null, null, null));

        // 初始 DRAFT：不允许请求
        assertThat(appService.isRequestAllowed(TEST_APP)).isFalse();
        // 草稿不能直接注销
        assertThatThrownBy(() -> appService.cancel(TEST_APP)).isInstanceOf(BizException.class);

        appService.enable(TEST_APP);
        assertThat(appService.detail(TEST_APP).status()).isEqualTo("ENABLED");
        assertThat(appService.isRequestAllowed(TEST_APP)).isTrue();

        // 启用状态不能重复启用
        assertThatThrownBy(() -> appService.enable(TEST_APP)).isInstanceOf(BizException.class);

        appService.disable(TEST_APP);
        assertThat(appService.isRequestAllowed(TEST_APP)).isFalse();
        appService.cancel(TEST_APP);
        assertThat(appService.detail(TEST_APP).status()).isEqualTo("CANCELLED");
    }

    @Test
    void 应用标识重复与格式校验() {
        assertThatThrownBy(() -> appService.create(new AppRequest("fastmoss", "重复", null,
                null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");
        assertThatThrownBy(() -> appService.create(new AppRequest("非法 标识", "格式", null,
                null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("应用标识");
    }

    // ---------- 接口类型互斥校验矩阵 ----------

    @Test
    void 出站接口禁止回调地址与ack字段() {
        setupTestApp();
        long groupId = createTestGroup();

        InterfaceRequest req = baseOutboundReq(groupId);
        assertThatThrownBy(() -> interfaceService.create(
                new InterfaceRequest(req.code() + "X", req.name(), req.ifType(), req.method(),
                        req.path() + "/x", req.protocolIn(), req.protocolOut(), req.appId(), req.groupId(),
                        req.upstreamPath(), "http://cb.example.com", null, 3000, 4, null, 1,
                        req.params(), req.bodies(), req.mappings(),
                        List.of(new FieldDefDto("ACK", "code", "string", null, 1)), req.bindings())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许配置回调地址");
    }

    @Test
    void 入站接口必填回调地址与送达报文() {
        setupTestApp();
        long groupId = createTestGroup();

        // 缺回调地址
        assertThatThrownBy(() -> interfaceService.create(new InterfaceRequest(
                "IF-M1-IN-1", "入站缺地址", "INBOUND", "POST", "/cb/m1/in1",
                "JSON", "JSON", TEST_APP, groupId,
                null, null, null, 3000, 4, null, 1,
                List.of(new ParamDto("IN", "event", "string", true, null, 1)), // 仅 IN 侧 → 送达报文缺失
                List.of(), List.of(),
                List.of(new FieldDefDto("ACK", "code", "string", null, 1)), List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必填回调地址");
    }

    // ---------- 分组 ----------

    @Test
    void 分组归属校验与组下有接口禁止删除() {
        setupTestApp();
        long groupId = createTestGroup();

        // 创建组必须指定存在的应用
        assertThatThrownBy(() -> groupService.create(new GroupRequest("NO-SUCH-APP", "越权组", 0)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("应用不存在");
        // 组名应用内唯一
        assertThatThrownBy(() -> groupService.create(new GroupRequest(TEST_APP, "测试分组", 0)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");

        // 接口归属校验（两级下拉）：分组属于 fastmoss、接口归属填 TEST_APP → 拒绝
        long fmGroup = groupService.list("fastmoss").get(0).id();
        assertThatThrownBy(() -> interfaceService.create(new InterfaceRequest(
                "IF-M1-X", "越权接口", "OUTBOUND", "POST", "/test/m1/x",
                "JSON", "JSON", TEST_APP, fmGroup,
                "/v1/x", null, null, 3000, 4, null, 1,
                baseOutboundReq(groupId).params(), List.of(), List.of(),
                List.of(new FieldDefDto("RESP", "total", "number", null, 1)), List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于所选应用");

        // 组下有接口禁止删除（M1 评审确认点 3）
        interfaceService.create(baseOutboundReq(groupId));
        assertThatThrownBy(() -> groupService.delete(groupId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("禁止删除");
    }

    // ---------- 凭证（M0-04 状态机） ----------

    @Test
    void 凭证加密落库遮显与状态机流转() {
        setupTestApp();

        // 更新（一步）：ACTIVE + 加密落库（库中无明文）
        credentialService.update(TEST_APP, new UpdateRequest("OUTBOUND", "secret-value-1234"));
        assertThat(credentialRepository.countByCredentialText(TEST_APP, "secret-value-1234")).isZero();

        List<CredentialView> views = credentialService.listViews(TEST_APP);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).fingerprint()).isEqualTo("1234");
        assertThat(views.get(0).status()).isEqualTo("ACTIVE");

        // 更新第二次：旧 ACTIVE → ROTATING（并存窗口），新 → ACTIVE
        credentialService.update(TEST_APP, new UpdateRequest("OUTBOUND", "new-secret-5678"));
        views = credentialService.listViews(TEST_APP);
        assertThat(views).extracting(CredentialView::status).containsExactlyInAnyOrder("ACTIVE", "ROTATING");
        CredentialView active = views.stream().filter(v -> "ACTIVE".equals(v.status())).findFirst().orElseThrow();
        assertThat(active.fingerprint()).isEqualTo("5678");

        // 重置：旧全部 RETIRED，新 ACTIVE
        credentialService.reset(TEST_APP, new ResetRequest("OUTBOUND", "reset-secret-90ab"));
        views = credentialService.listViews(TEST_APP);
        assertThat(views.stream().filter(v -> "ACTIVE".equals(v.status()))).hasSize(1);
        assertThat(views.stream().filter(v -> "ACTIVE".equals(v.status())).findFirst().orElseThrow().fingerprint())
                .isEqualTo("90ab");

        // 即时失效最后一个 ACTIVE → 返回告警
        long activeId = views.stream().filter(v -> "ACTIVE".equals(v.status())).findFirst().orElseThrow().id();
        String warning = credentialService.retire(TEST_APP, activeId);
        assertThat(warning).contains("立即补发");
    }

    // ---------- 删除守卫（存在运行数据仅允许下线） ----------

    @Test
    void 接口存在运行数据禁止删除() {
        setupTestApp();
        long groupId = createTestGroup();
        long id = interfaceService.create(baseOutboundReq(groupId));

        // 模拟该接口已被调用（产生运行数据）
        jdbcTemplate.update("""
                INSERT INTO outbound_request (interface_id, app_id, biz_id, in_payload, status,
                                              attempt_count, max_attempts, trace_id)
                VALUES (?, ?, ?, '{}', 'SUCCESS', 1, 5, 'trace-delete-guard')
                """, id, TEST_APP, "biz-delete-guard");
        assertThatThrownBy(() -> interfaceService.delete(id))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("运行数据");

        // 清理运行数据后可删除（且删除后详情 404）
        jdbcTemplate.update("DELETE FROM outbound_request WHERE interface_id = ?", id);
        interfaceService.delete(id);
        assertThatThrownBy(() -> interfaceService.detail(id)).isInstanceOf(BizException.class);
    }

    // ---------- 凭证删除（仅 RETIRED 可删，ACTIVE/ROTATING 受保护） ----------

    @Test
    void 凭证仅失效后可删除() {
        setupTestApp();
        credentialService.update(TEST_APP, new UpdateRequest("OUTBOUND", "secret-del-1"));

        CredentialView active = credentialService.listViews(TEST_APP).stream()
                .filter(v -> "ACTIVE".equals(v.status()))
                .findFirst().orElseThrow();
        // ACTIVE 禁止删除
        assertThatThrownBy(() -> credentialService.delete(TEST_APP, active.id()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("RETIRED");

        // 失效后（转 RETIRED）可删除
        credentialService.retire(TEST_APP, active.id());
        credentialService.delete(TEST_APP, active.id());
        assertThat(credentialService.listViews(TEST_APP)).isEmpty();
    }

    // ---------- 适配器 D6：同 impl 至多 1 条启用（create 默认启用 + update 双路径） ----------

    @Test
    void 适配器同impl双启用拒绝() {
        // 用种子不存在的 impl（HmacAuthAdapter 仅元数据，无实现 Bean 也可创建）；
        // params 需补齐 schema 必填字段
        String params = "{\"signatureAlgorithm\":\"HMAC-SHA256\",\"signatureHeader\":\"X-Signature\",\"timestampToleranceSeconds\":300}";
        adapterService.create(new AdapterRequest("M1-TEST-ADP1", "测试适配器1", "auth", "HmacAuthAdapter",
                true, "1.0", params));
        try {
            // create 时 enabled 缺省（默认 true）→ 拒绝
            assertThatThrownBy(() -> adapterService.create(new AdapterRequest("M1-TEST-ADP2", "测试适配器2",
                    "auth", "HmacAuthAdapter", null, "1.0", params)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("至多 1 条启用");
            // update 把 disabled 改为 enabled → 拒绝（中危 #4：update 路径漏检）
            adapterService.create(new AdapterRequest("M1-TEST-ADP3", "测试适配器3", "auth", "HmacAuthAdapter",
                    false, "1.0", params));
            assertThatThrownBy(() -> adapterService.update("M1-TEST-ADP3",
                    new AdapterRequest("M1-TEST-ADP3", "测试适配器3", "auth", "HmacAuthAdapter",
                            true, "1.0", params)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("至多 1 条启用");
        } finally {
            adapterService.delete("M1-TEST-ADP1");
            if (adapterRepository("M1-TEST-ADP3")) {
                adapterService.delete("M1-TEST-ADP3");
            }
        }
    }

    private boolean adapterRepository(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adapter WHERE id = ?", Integer.class, id) > 0;
    }

    // ---------- 凭证重复激活（CAS 状态校验） ----------

    @Test
    void 凭证激活后重复激活被拒() {
        setupTestApp();
        credentialService.update(TEST_APP, new UpdateRequest("OUTBOUND", "secret-aaaa"));
        credentialService.update(TEST_APP, new UpdateRequest("OUTBOUND", "secret-bbbb")); // 旧 ACTIVE → ROTATING

        CredentialView rotating = credentialService.listViews(TEST_APP).stream()
                .filter(v -> "ROTATING".equals(v.status()))
                .findFirst().orElseThrow();
        credentialService.activate(TEST_APP, rotating.id());

        // 激活后目标已 ACTIVE，重复激活被状态校验拒绝
        assertThatThrownBy(() -> credentialService.activate(TEST_APP, rotating.id()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅待激活");
    }

    // ---------- 目标地址格式校验（中危 #7） ----------

    @Test
    void 接口目标地址格式校验() {
        setupTestApp();
        long groupId = createTestGroup();

        // OUTBOUND 上游路径含协议 → 拒绝
        assertThatThrownBy(() -> interfaceService.create(new InterfaceRequest(
                "IF-M1-URL-1", "x", "OUTBOUND", "POST", "/t/url1", "JSON", "JSON", TEST_APP, groupId,
                "https://evil.com/path", null, null, 3000, 4, null, 1,
                baseOutboundReq(groupId).params(), List.of(), List.of(), List.of(), List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("相对路径");
        // INBOUND 回调地址非完整 URL → 拒绝
        assertThatThrownBy(() -> interfaceService.create(new InterfaceRequest(
                "IF-M1-URL-2", "x", "INBOUND", "POST", "/t/url2", "JSON", "JSON", TEST_APP, groupId,
                null, "not-a-url", null, 3000, 4, null, 1,
                List.of(new ParamDto("IN", "e", "string", true, null, 1),
                        new ParamDto("OUT", "e", "string", true, null, 1)),
                List.of(), List.of(),
                List.of(new FieldDefDto("ACK", "code", "string", null, 1)), List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("完整 URL");
    }

    // ---------- 乐观锁 ----------

    @Test
    void 接口更新乐观锁冲突() {
        setupTestApp();
        long groupId = createTestGroup();
        long id = interfaceService.create(baseOutboundReq(groupId));

        // 用旧 version（1）提交 → 冲突（更新成功版本已自增为 2 后再用 1 提交应失败）
        InterfaceResponse fresh = interfaceService.detail(id);
        InterfaceRequest base = baseOutboundReq(groupId); // 子表用请求 DTO 类型（与响应 model 类型不同）
        InterfaceRequest staleReq = new InterfaceRequest(
                fresh.code(), "改名字", fresh.ifType(), fresh.method(), fresh.path(),
                fresh.protocolIn(), fresh.protocolOut(), fresh.appId(), fresh.groupId(),
                fresh.upstreamPath(), fresh.callbackUrl(), null,
                fresh.timeoutMs(), fresh.maxRetries(), null, 1, // 旧 version
                base.params(), base.bodies(), base.mappings(), base.fieldDefs(), base.bindings());
        interfaceService.update(id, staleReq); // 第一次用当前 version 成功
        assertThatThrownBy(() -> interfaceService.update(id, staleReq))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("乐观锁");
    }

    // ---------- helpers ----------

    private void setupTestApp() {
        if (!appRepository.existsById(TEST_APP)) {
            appService.create(new AppRequest(TEST_APP, "测试应用", null, null, null, null,
                    null, null, null, null, null, null));
            appService.enable(TEST_APP);
        }
    }

    private long createTestGroup() {
        if (!groupService.list(TEST_APP).isEmpty()) {
            return groupService.list(TEST_APP).get(0).id();
        }
        return groupService.create(new GroupRequest(TEST_APP, "测试分组", 0));
    }

    private InterfaceRequest baseOutboundReq(long groupId) {
        List<ParamDto> params = List.of(
                new ParamDto("IN", "name", "string", true, null, 1),
                new ParamDto("OUT", "name", "string", true, null, 1));
        return new InterfaceRequest(
                "IF-M1-OUT-1", "测试出站接口", "OUTBOUND", "POST", "/test/m1/out1",
                "JSON", "JSON", TEST_APP, groupId,
                "/v1/test", null, null, 3000, 4, null, 1,
                params, List.of(), new ArrayList<>(),
                List.of(new FieldDefDto("RESP", "total", "number", null, 1)),
                List.of(new BindingDto("MESSAGE", null, null)));
    }
}
