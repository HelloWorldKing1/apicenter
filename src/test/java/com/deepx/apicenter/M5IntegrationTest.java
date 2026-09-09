package com.deepx.apicenter;

import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.MappingDto;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdapterRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.service.AdapterService;
import com.deepx.apicenter.service.AppService;
import com.deepx.apicenter.service.GroupService;
import com.deepx.apicenter.service.InterfaceService;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5 集成测试（M5 计划 §4，S1-S6 编码落地）：
 * S1 变更生成快照 / S2 回滚行为等价 + 版本只增 + status 不变 / S3 灰度路由（同 impl 双版本）/
 * S4 灰度即时生效（事件失效）+ test 端点 chainTrace / S5 灰度目标缺失回退 /
 * S6 回滚与缓存联动；另含 D6 灰度放宽（同 impl 多版本启用）与 APP 事件全清。
 * WireMock 扮演上游；经真实网关 HTTP 路径（RANDOM_PORT）。后台调度拉长（用例手动驱动，确定性）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-fixed-delay-ms=3600000"
})
class M5IntegrationTest {

    private static final String TEST_APP = "M5-TEST-APP";
    private static final int WM_PORT = 18080;
    private static final String WM_BASE = "http://localhost:" + WM_PORT;

    /** 同 impl（EnvelopeMessageAdapter）双版本灰度：9.1 codeField=code / 9.2 codeField=status */
    private static final String ENV_IMPL = "EnvelopeMessageAdapter";
    private static final String ENV_V1 = "M5-ENV-1"; // version 9.1：code=0 为成功
    private static final String ENV_V2 = "M5-ENV-2"; // version 9.2：status=ok 为成功

    /** 上游响应 A：code=0 但 status=bad → 仅 9.1 判成功；B：code=x 但 status=ok → 仅 9.2 判成功 */
    private static final String RESP_A = "{\"data\":{\"v\":1},\"code\":\"0\",\"status\":\"bad\"}";
    private static final String RESP_B = "{\"data\":{\"v\":2},\"code\":\"x\",\"status\":\"ok\"}";

    private static WireMockServer wireMock;

    @LocalServerPort
    private int port;

    private final RestClient restClient = buildTestClient();

    private static RestClient buildTestClient() {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory();
        factory.setReadTimeout(java.time.Duration.ofSeconds(30));
        return RestClient.builder()
                .requestFactory(factory)
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (req, resp) -> { })
                .build();
    }

    @Autowired
    private AppService appService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private InterfaceService interfaceService;
    @Autowired
    private AdapterService adapterService;
    @Autowired
    private AppRepository appRepository;
    @Autowired
    private AdapterRepository adapterRepository;
    @Autowired
    private InterfaceRepository interfaceRepository;
    @Autowired
    private OutboundRequestRepository outboundRequestRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private long groupId;
    private long verIfaceId;   // S1/S2/S6：映射 rename ↔ enumMap 快照 / 回滚 / 版本列表
    private long grayIfaceId;  // S3/S4/S5：同 impl 双版本灰度路由 / 即时生效 / 目标缺失回退
    private long noopIfaceId;  // APP 事件全清：无消息绑定的接口（应用默认 MESSAGE 变化即时生效）

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().port(WM_PORT));
        wireMock.start();
        com.github.tomakehurst.wiremock.client.WireMock.configureFor("localhost", WM_PORT);
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setup() {
        wireMock.resetAll();
        ensureAdapters();
        // 每个用例全量重建（先清后建）：快照 / 版本计数断言需自洽起点（与 M4 共享态断言不同）
        cleanupApp();
        appService.create(new AppRequest(TEST_APP, "M5 测试供应商", null,
                null, null, null, WM_BASE, null, null, null, null,
                "M5 集成测试：版本快照 / 灰度（Noop 鉴权，消息适配器接口级绑定）"));
        appService.enable(TEST_APP);
        groupId = groupService.create(new GroupRequest(TEST_APP, "测试分组", 0));
        verIfaceId = createIface("IF-M5-VER", "/m5/ver", "/up-m5", List.of(
                new MappingDto("state", "rename", "order_state", null, "KEEP", 1)));
        interfaceService.publish(verIfaceId);
        grayIfaceId = createIface("IF-M5-GRAY", "/m5/gray", "/up-gray", List.of());
        interfaceService.publish(grayIfaceId);
        noopIfaceId = createIface("IF-M5-NOOP", "/m5/noop", "/up-noop", List.of());
        interfaceService.publish(noopIfaceId);
        // 灰度绑定初始 = M5-ENV-1（v9.1，version 空 = 矩阵 #2）
        setMessageBinding(grayIfaceId, ENV_V1, null);
    }

    @AfterEach
    void cleanup() {
        wireMock.resetAll();
        cleanupApp();
        for (String id : List.of("M5-ENV-1", "M5-ENV-2", "M5-ENV-3")) {
            if (adapterRepository.existsById(id)) {
                adapterRepository.delete(id);
            }
        }
    }

    private void cleanupApp() {
        if (appRepository.existsById(TEST_APP)) {
            outboundRequestRepository.deleteByApp(TEST_APP);
            jdbcTemplate.queryForList("SELECT id FROM interface WHERE app_id = ?", Long.class, TEST_APP)
                    .forEach(interfaceRepository::deleteCascade);
            appRepository.deleteCascade(TEST_APP);
        }
    }

    // ---------- S1/S2/S6：版本快照与回滚 ----------

    @Test
    void s1_创建与更新生成快照_版本号只增_changeNote可追溯() throws Exception {
        // 创建 → v1 快照
        assertSnapshotCount(verIfaceId, 1);
        assertSnapshotVersionExists(verIfaceId, new BigDecimal("1.0"));
        BigDecimal v1 = versionOf(verIfaceId);
        assertThat(v1).isEqualByComparingTo(new BigDecimal("1.0"));

        // PUT 全量更新（映射 rename → enumMap）+ X-Change-Note → v2 快照
        stubUpstream("/up-m5");
        updateMapping(verIfaceId, new BigDecimal("1.0"), List.of(
                new MappingDto("state", "enumMap", "order_state", "PAID→已支付", "KEEP", 1)));
        assertSnapshotCount(verIfaceId, 2);
        String v2Json = configJsonOf(verIfaceId, new BigDecimal("1.1"));
        assertThat(v2Json).contains("enumMap").contains("已支付").doesNotContain("\"rename\"");

        // 更新后立即调用 → 新映射生效（S1 行为断言；INTERFACE 事件已失效链缓存）
        call("/m5/ver", "{\"state\":\"PAID\"}");
        wireMock.verify(postRequestedFor(urlEqualTo("/up-m5"))
                .withRequestBody(equalToJson("{\"order_state\":\"已支付\"}")));
    }

    @Test
    void s2_回滚行为等价_版本只增_status不变_目标缺失与乐观锁冲突拒绝() throws Exception {
        stubUpstream("/up-m5");
        // 基线：v1 rename → v2 enumMap（先调一次确认 v2 语义）
        BigDecimal v1 = versionOf(verIfaceId);
        updateMapping(verIfaceId, v1, List.of(
                new MappingDto("state", "enumMap", "order_state", "PAID→已支付", "KEEP", 1)));
        call("/m5/ver", "{\"state\":\"PAID\"}");
        wireMock.verify(postRequestedFor(urlEqualTo("/up-m5"))
                .withRequestBody(equalToJson("{\"order_state\":\"已支付\"}")));
        BigDecimal v2 = versionOf(verIfaceId);
        assertThat(v2).isEqualByComparingTo(new BigDecimal("1.1"));

        // 回滚 v1.0（HTTP rollback：变更说明极简 = 「回滚至 v1.0」；乐观锁 currentVersion=1.1）
        ResponseEntity<byte[]> rb = postAdmin("/api/admin/interfaces/" + verIfaceId + "/rollback",
                "{\"targetVersion\":1.0,\"currentVersion\":1.1}");
        assertThat(rb.getStatusCode().value()).isEqualTo(200);

        BigDecimal v3 = versionOf(verIfaceId);
        assertThat(v3).isEqualByComparingTo(new BigDecimal("1.2")); // 每次变更 / 回滚 +0.1，不回退
        assertThat(statusOf(verIfaceId)).isEqualTo("PUBLISHED"); // status 保持不变（快照不含 status）
        assertSnapshotCount(verIfaceId, 3);
        String note = latestChangeNote(verIfaceId);
        assertThat(note).isEqualTo("回滚至 v1.0");
        // 回滚行仍应生成 type=ROLLBACK 的结构化变更详情（相对回滚前版本的字段差异）
        String rollbackDetail = jdbcTemplate.queryForObject(
                "SELECT change_detail FROM interface_snapshot WHERE interface_id = ? AND version = ?",
                String.class, verIfaceId, new BigDecimal("1.2"));
        assertThat(rollbackDetail).isNotNull().contains("\"type\":\"ROLLBACK\"").contains("\"fields\"");

        // 回滚后立即调用（S6：回滚发布 INTERFACE 事件）→ rename 语义恢复（不再枚举映射）
        // 先清 WireMock 请求计数（上面的 enumMap 调用记录清掉，verify(0) 才具鉴别力）
        wireMock.resetAll();
        stubFor(post("/up-m5").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
        call("/m5/ver", "{\"state\":\"PAID\"}");
        wireMock.verify(postRequestedFor(urlEqualTo("/up-m5"))
                .withRequestBody(equalToJson("{\"order_state\":\"PAID\"}")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/up-m5"))
                .withRequestBody(equalToJson("{\"order_state\":\"已支付\"}")));

        // 版本列表 / 详情端点
        String listBody = body(get("/api/admin/interfaces/" + verIfaceId + "/versions?page=1&pageSize=10"));
        assertThat(listBody).contains("\"total\":3");
        String detailBody = body(get("/api/admin/interfaces/" + verIfaceId + "/versions/1.0"));
        assertThat(detailBody).contains("\"version\":1.0").contains("\"configJson\"")
                .contains("order_state").contains("rename"); // configJson 为 JSON 字符串（引号转义），查子串即可

        // 目标版本不存在 → 40403 语义信封
        ResponseEntity<byte[]> missing = postAdmin("/api/admin/interfaces/" + verIfaceId + "/rollback",
                "{\"targetVersion\":99.9,\"operator\":\"x\",\"currentVersion\":1.2}");
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(body(missing)).contains("40403");

        // 乐观锁冲突：currentVersion 过期（提交旧版本）→ 40001
        ResponseEntity<byte[]> stale = postAdmin("/api/admin/interfaces/" + verIfaceId + "/rollback",
                "{\"targetVersion\":1.0,\"operator\":\"x\",\"currentVersion\":1.0}");
        assertThat(stale.getStatusCode().value()).isEqualTo(400);
        assertThat(body(stale)).contains("40001").contains("已被他人修改");
    }

    // ---------- S3/S4/S5：绑定切换即时生效（D6' 弃用灰度版本路由——绑定即实例，version 仅记录） ----------

    @Test
    void s3_绑定即实例_version不再路由_切实例即时生效() throws Exception {
        stubUpstream("/up-gray");
        // 初始绑定 ENV_V1（v9.1 code=0）：RESP_A 成功 → 业务数据 v=1
        wireMock.resetAll();
        stubFor(post("/up-gray").willReturn(okJson(RESP_A)));
        String a = body(call("/m5/gray", "{}"));
        assertThat(a).contains("\"code\":0").contains("\"v\":1").doesNotContain("\"status\"");

        // D1 验证：绑定行带 version=9.2 但 adapter_id 仍 ENV_V1 → 恒用 ENV_V1（不再按 version 重定位）→ 仍 v=1
        setMessageBinding(grayIfaceId, ENV_V1, "9.2");
        wireMock.resetAll();
        stubFor(post("/up-gray").willReturn(okJson(RESP_A)));
        assertThat(body(call("/m5/gray", "{}"))).contains("\"v\":1").doesNotContain("\"bad\"");

        // 真切换 = 换绑定实例 ENV_V2（v9.2 status=ok）：RESP_B 成功 → v=2（即时生效，不等 TTL）
        setMessageBinding(grayIfaceId, ENV_V2, null);
        wireMock.resetAll();
        stubFor(post("/up-gray").willReturn(okJson(RESP_B)));
        String b = body(call("/m5/gray", "{}"));
        assertThat(b).contains("\"code\":0").contains("\"v\":2").doesNotContain("\"bad\"");
    }

    @Test
    void s4_绑定切换即时生效_事件失效_chainTrace同步() throws Exception {
        stubUpstream("/up-gray");
        wireMock.resetAll();
        stubFor(post("/up-gray").willReturn(okJson(RESP_A)));
        assertThat(body(call("/m5/gray", "{}"))).contains("\"v\":1");

        // 切绑定实例 ENV_V2 → 立即调用（INTERFACE 事件 → 缓存精准失效 → 重新装配）
        setMessageBinding(grayIfaceId, ENV_V2, null);
        wireMock.resetAll();
        stubFor(post("/up-gray").willReturn(okJson(RESP_B)));
        assertThat(body(call("/m5/gray", "{}"))).contains("\"v\":2");

        // test 端点（chainTrace 留痕通道 3）：强制实时解析 → 反映当前绑定实例 ENV_V2（v9.2）
        ResponseEntity<byte[]> test = postAdmin("/api/admin/interfaces/" + grayIfaceId + "/test", null);
        assertThat(test.getStatusCode().value()).isEqualTo(200);
        String t = body(test);
        assertThat(t).contains("\"chainTrace\"").contains("M5-ENV-2").contains("\"9.2\"")
                .contains("\"result\"").contains("\"v\":2");

        // 切回 ENV_V1 → 立即恢复 v1 行为
        setMessageBinding(grayIfaceId, ENV_V1, null);
        wireMock.resetAll();
        stubFor(post("/up-gray").willReturn(okJson(RESP_A)));
        assertThat(body(call("/m5/gray", "{}"))).contains("\"v\":1");
    }

    @Test
    void s5_停用即回退应用默认与Noop_重新启用恢复() throws Exception {
        stubUpstream("/up-gray");
        // 启用态 v9.1 剥壳正常（v=1）
        setMessageBinding(grayIfaceId, ENV_V1, null);
        wireMock.resetAll();
        stubFor(post("/up-gray").willReturn(okJson(RESP_A)));
        assertThat(body(call("/m5/gray", "{}"))).contains("\"v\":1");

        // 停用绑定实例 ENV_V1 → 不可用 → 应用默认（无）→ 平台默认 Noop 直通（不再按信封剥壳）
        adapterService.enable(ENV_V1, false);
        wireMock.resetAll();
        stubFor(post("/up-gray").willReturn(okJson(RESP_A)));
        assertThat(body(call("/m5/gray", "{}"))).contains("\"code\":0").contains("\"status\":\"bad\"");

        // 重新启用 → 恢复信封剥壳（v9.1）
        adapterService.enable(ENV_V1, true);
        wireMock.resetAll();
        stubFor(post("/up-gray").willReturn(okJson(RESP_A)));
        assertThat(body(call("/m5/gray", "{}"))).contains("\"v\":1");
    }

    @Test
    void s6_应用默认消息适配器变更_APP事件全清即时生效() throws Exception {
        stubUpstream("/up-noop");
        // 无消息绑定接口 → 应用默认（无）→ Noop 直通
        wireMock.resetAll();
        stubFor(post("/up-noop").willReturn(okJson(RESP_A)));
        assertThat(body(call("/m5/noop", "{}"))).contains("\"status\":\"bad\"");
        // 预热过该链（缓存已建）；改应用默认消息适配器 → APP 事件全清 → 立即走 v9.1 信封剥壳
        appRepository.findById(TEST_APP).orElseThrow();
        appService.update(TEST_APP, new AppRequest(TEST_APP, "M5 测试供应商", null,
                null, null, ENV_V1, WM_BASE, null, null, null, null,
                "M5 集成测试（应用默认消息适配器 = ENV_V1）"));
        wireMock.resetAll();
        stubFor(post("/up-noop").willReturn(okJson(RESP_A)));
        assertThat(body(call("/m5/noop", "{}"))).contains("\"v\":1").doesNotContain("\"bad\"");
    }

    @Test
    void d6新语义_同impl同版本可多启用_name全表唯一() {
        // M5-ENV-1 / M5-ENV-2 已在 setup 启用（不同 version）——同 impl 多版本并存仍成立
        assertThat(adapterRepository.findById(ENV_V1).orElseThrow().enabled()).isTrue();
        assertThat(adapterRepository.findById(ENV_V2).orElseThrow().enabled()).isTrue();
        // D6'：同 (impl, version) 也允许第二条启用（与 ENV_V1 同为 9.1，灰度路由已弃用、实例靠 id+name 区分）
        adapterRepository.insert(new AdapterRow("M5-ENV-3", "信封 v1 双实例", "message",
                ENV_IMPL, false, "9.1", "{\"envelope\":\"data\",\"codeField\":\"code\",\"successValue\":\"0\",\"messageField\":\"message\"}",
                null, null));
        adapterService.enable("M5-ENV-3", true); // 不再拒绝：与 ENV_V1 同 (impl=EnvelopeMessageAdapter, 9.1) 并存启用
        assertThat(adapterRepository.findById("M5-ENV-3").orElseThrow().enabled()).isTrue();
        // name 全表唯一（D2）：create 撞现有名（ENV_V1 的「信封 v1（code=0）」）→ 拒绝
        assertThatThrownBy(() -> adapterService.create(new com.deepx.apicenter.dto.AdapterDtos.AdapterRequest(
                "M5-ENV-DUP", "信封 v1（code=0）", "message", ENV_IMPL, true, "9.1",
                "{\"envelope\":\"data\",\"codeField\":\"code\",\"successValue\":\"0\",\"messageField\":\"message\"}")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("名称已存在");
    }

    // ---------- helpers ----------

    private void ensureAdapters() {
        insertIfAbsent(ENV_V1, "信封 v1（code=0）", ENV_IMPL, "9.1",
                "{\"envelope\":\"data\",\"codeField\":\"code\",\"successValue\":\"0\",\"messageField\":\"message\"}");
        insertIfAbsent(ENV_V2, "信封 v2（status=ok）", ENV_IMPL, "9.2",
                "{\"envelope\":\"data\",\"codeField\":\"status\",\"successValue\":\"ok\",\"messageField\":\"message\"}");
        adapterService.enable(ENV_V1, true);
        adapterService.enable(ENV_V2, true);
    }

    private void insertIfAbsent(String id, String name, String impl, String version, String params) {
        if (!adapterRepository.existsById(id)) {
            adapterRepository.insert(new AdapterRow(id, name, "message", impl, false, version, params, null, null));
        }
    }

    private long createIface(String code, String path, String upstream, List<MappingDto> mappings) {
        return interfaceService.create(new InterfaceRequest(
                code, code, "OUTBOUND", "POST", path,
                "JSON", "JSON", TEST_APP, groupId, upstream, null, null,
                3000, 4, "M5 集成测试接口", 1,
                List.of(), List.of(), mappings, List.of(), List.of()));
    }

    /** 全量更新映射（版本号取当前——乐观锁） */
    private void updateMapping(long id, BigDecimal currentVersion, List<MappingDto> mappings) {
        InterfaceRow row = interfaceRepository.findById(id).orElseThrow();
        interfaceService.update(id, new InterfaceRequest(
                row.code(), row.name(), row.ifType(), row.method(), row.path(),
                row.protocolIn(), row.protocolOut(), row.appId(), row.groupId(),
                row.upstreamPath(), row.callbackUrl(), null,
                row.timeoutMs(), row.maxRetries(), row.desc(), currentVersion,
                List.of(), List.of(), mappings, List.of(), List.of()));
    }

    /** 全量更新 MESSAGE 绑定（version 空 = 矩阵 #2；非空 = 矩阵 #3 灰度） */
    private void setMessageBinding(long id, String adapterId, String version) {
        InterfaceRow row = interfaceRepository.findById(id).orElseThrow();
        List<BindingDto> bindings = version == null || version.isBlank()
                ? List.of(new BindingDto("MESSAGE", adapterId, null))
                : List.of(new BindingDto("MESSAGE", adapterId, version));
        interfaceService.update(id, new InterfaceRequest(
                row.code(), row.name(), row.ifType(), row.method(), row.path(),
                row.protocolIn(), row.protocolOut(), row.appId(), row.groupId(),
                row.upstreamPath(), row.callbackUrl(), null,
                row.timeoutMs(), row.maxRetries(), row.desc(), row.version(),
                List.of(), List.of(), List.of(), List.of(), bindings));
    }

    private void stubUpstream(String path) {
        wireMock.resetAll();
        stubFor(post(path).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
    }

    private ResponseEntity<byte[]> call(String path, String jsonBody) {
        return restClient.post().uri(url(path))
                .headers(h -> h.addAll(jsonHeaders()))
                .body(jsonBody.getBytes(StandardCharsets.UTF_8))
                .retrieve().toEntity(byte[].class);
    }

    private ResponseEntity<byte[]> get(String path) {
        return restClient.get().uri(url(path))
                .headers(h -> h.addAll(jsonHeaders()))
                .retrieve().toEntity(byte[].class);
    }

    private ResponseEntity<byte[]> postAdmin(String path, String json) {
        var spec = restClient.post().uri(url(path)).headers(h -> h.addAll(jsonHeaders()));
        if (json != null) {
            spec = spec.body(json.getBytes(StandardCharsets.UTF_8));
        }
        return spec.retrieve().toEntity(byte[].class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String body(ResponseEntity<byte[]> resp) {
        return resp.getBody() == null ? "" : new String(resp.getBody(), StandardCharsets.UTF_8);
    }

    private BigDecimal versionOf(long id) {
        return interfaceRepository.findById(id).orElseThrow().version();
    }

    private String statusOf(long id) {
        return interfaceRepository.findById(id).orElseThrow().status();
    }

    private void assertSnapshotCount(long id, int expected) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interface_snapshot WHERE interface_id = ?", Long.class, id);
        assertThat(n).isEqualTo((long) expected);
    }

    private void assertSnapshotVersionExists(long id, BigDecimal version) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interface_snapshot WHERE interface_id = ? AND version = ?",
                Long.class, id, version);
        assertThat(n).isEqualTo(1L);
    }

    private String configJsonOf(long id, BigDecimal version) {
        return jdbcTemplate.queryForObject(
                "SELECT config_json FROM interface_snapshot WHERE interface_id = ? AND version = ?",
                String.class, id, version);
    }

    private String latestChangeNote(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT change_note FROM interface_snapshot WHERE interface_id = ? ORDER BY version DESC LIMIT 1",
                String.class, id);
    }
}
