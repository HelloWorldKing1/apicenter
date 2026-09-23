package com.deepx.apicenter;

import com.deepx.apicenter.dto.AdapterDtos.AdapterRequest;
import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupResponse;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.BodyDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.ParamDto;
import com.deepx.apicenter.repository.AccessAuthLogRepository;
import com.deepx.apicenter.repository.AccessAuthLogRepository.AccessAuthLogEntry;
import com.deepx.apicenter.service.AdapterService;
import com.deepx.apicenter.service.AppService;
import com.deepx.apicenter.service.ClientCredentialService;
import com.deepx.apicenter.service.ClientService;
import com.deepx.apicenter.service.GroupService;
import com.deepx.apicenter.service.InterfaceService;
import com.deepx.apicenter.service.InternalCallToken;
import com.deepx.apicenter.worker.AccessAuthLogWriter;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.reset;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 调用方鉴权**闸门接入**集成测试（2026-09-23，入站鉴权 B3；设计方案 §7/§10/§19）。
 *
 * <p>出口标志：**`ENFORCED` 下裸调 401 且运行表零增量**（拒绝不污染状态机）/ **审计三字段（IP·名称·方式）非空**
 * / **回调审计 `direction=CALLBACK`**（含 401xx 的 REJECT 回填）。
 *
 * <p>`mode` 是**启动期属性**（无法按用例切换）：本类跑 `ENFORCED` —— 最热、最需要端到端验证的那条路径；
 * `OFF`/`OPTIONAL` 逐行语义由 `ClientAuthVerifierTest`（23 例）覆盖，且 `OFF` 正是**其余全部集成测试**的运行态
 * （默认值 ⇒ 既有行为不受影响，等于被全套件回归）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.api-center.retry-worker-fixed-delay-ms=3600000",
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-initial-delay-ms=3600000",
        "app.api-center.alert-worker-initial-delay-ms=3600000",
        // 本类测网关鉴权，不走管理面鉴权
        "app.api-center.auth.enabled=false",
        // 强制模式：默认 OFF 时闸门早退，测不到
        "app.api-center.client-auth.mode=ENFORCED"
})
class ClientAuthGateIntegrationTest {

    private static final int WM_PORT = 18080;
    private static final String WM_BASE = "http://localhost:" + WM_PORT;
    private static final String TEST_APP = "TEST-CA-APP";
    private static final String TEST_GROUP = "CA 默认分组";
    private static final String CLIENT = "TEST-CA-CLIENT";
    private static final String API_KEY = "b3-api-key-1234";

    private static WireMockServer wireMock;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppService appService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private InterfaceService interfaceService;
    @Autowired
    private AdapterService adapterService;
    @Autowired
    private ClientService clientService;
    @Autowired
    private ClientCredentialService clientCredentialService;
    @Autowired
    private InternalCallToken internalCallToken;
    @Autowired
    private AccessAuthLogRepository accessAuthLogRepository;
    @Autowired
    private AccessAuthLogWriter accessAuthLogWriter;
    @Autowired
    private com.deepx.apicenter.repository.AppRepository appRepository;
    @Autowired
    private com.deepx.apicenter.repository.AdapterRepository adapterRepository;
    @Autowired
    private com.deepx.apicenter.repository.ClientAppRepository clientAppRepository;
    @Autowired
    private com.deepx.apicenter.repository.InterfaceRepository interfaceRepository;
    @Autowired
    private com.deepx.apicenter.repository.CredentialRepository credentialRepository;
    @Autowired
    private com.deepx.apicenter.service.InboundAuthSettingService inboundAuthSettingService;
    @Autowired
    private com.deepx.apicenter.service.InboundCredentialService inboundCredentialService;

    private RestClient http;
    private String outboundPath;
    private String callbackPath;
    private String traceCounter = "b3-" + System.nanoTime();

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().port(WM_PORT));
        wireMock.start();
        configureFor("localhost", WM_PORT);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void setUp() {
        reset();
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                // 禁用默认 4xx/5xx 抛异常，便于断言状态码（照 HttpErrorSemanticsTest）
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (req, resp) -> {
                })
                .build();
        cleanFixtures();
        long groupId = ensureFixtures();
        // 出站中转：上游 WireMock /echo（POST，返回 JSON）
        outboundPath = "/ca/echo";
        createOutboundInterface("IF-CA-ECHO", outboundPath, groupId);
        // 入站回调：回调地址指向 WireMock；绑定回调验签（坏签名触发 40100 → 审计 REJECT 回填）
        callbackPath = "/ca/callback";
        createInboundInterface("IF-CA-CB", callbackPath, groupId, createHmacAdapter(), WM_BASE + "/delivery-ok");
        // 调用方：API Key 方式 + 一个 ACTIVE 凭证
        clientService.create(new com.deepx.apicenter.dto.ClientDtos.ClientRequest(
                CLIENT, "B3 测试调用方", null, createApiKeyAdapter(), null, null, null, null, "B3 集成测试"));
        clientCredentialService.update(CLIENT, new UpdateRequest("API_KEY", API_KEY));
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
        // v1.2：平台设置与平台池是**全局**状态，用例结束必须复位，避免污染其他用例/手动验收
        inboundAuthSettingService.save(null, true, "b3-cleanup");
        credentialRepository.deleteByOwner(com.deepx.apicenter.repository.CredentialOwner.PLATFORM, null);
    }

    // ---------- 用例 ----------

    @Test
    void 开放集_无主体_凭平台池凭证可调用_并落审计归因_用例39() {
        stubFor(post(urlEqualTo("/echo")).willReturn(okJson("{\"ok\":true}")));
        // v1.2（D-CA-18/19）：不登记调用方也能调 —— 平台默认方式 + 平台共享池凭证
        inboundAuthSettingService.save(createApiKeyAdapter(), false, "b3-test");
        com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView issued =
                inboundCredentialService.prepare("PLATFORM", null, "API_KEY", "B3 开放集用例");
        inboundCredentialService.activate("PLATFORM", null, issued.id());

        String trace = nextTrace();
        var resp = callPost(outboundPath, "{}", trace, null, issued.plaintext());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        AccessAuthLogEntry audit = awaitAudit(trace);
        assertThat(audit).isNotNull();
        assertThat(audit.result()).isEqualTo("PASS");
        assertThat(audit.principalType()).isEqualTo("UNVERIFIED");        // 无主体 ⇒ 自报未验证
        assertThat(audit.credentialLabel()).isEqualTo("B3 开放集用例");   // 凭证归因（D-CA-20）
        assertThat(audit.credentialFingerprint()).isEqualTo(
                issued.plaintext().substring(issued.plaintext().length() - 4));
    }

    @Test
    void ENFORCED_裸调无凭证_401且运行表零增量且审计REJECT() {
        String trace = nextTrace();
        var resp = callPost(outboundPath, "{}", trace, null, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).contains("40107").contains("缺少主体标识头");

        // 拒绝发生在落运行表之前 ⇒ 不污染状态机（与 D-M4-6 同口径）
        assertThat(outboundRequestCount(trace)).isZero();

        AccessAuthLogEntry audit = awaitAudit(trace);
        assertThat(audit).isNotNull();
        assertThat(audit.direction()).isEqualTo("INBOUND_CALL");
        // v1.2：未带 X-Client-Id ⇒ 主体为「自报未验证」（UNVERIFIED），不再是 CLIENT
        //       （§6.2 主体语义表：自报 ≠ 已验证身份；只有命中「档案池」凭证或回调验签才是 CLIENT/SUPPLIER）
        assertThat(audit.principalType()).isEqualTo("UNVERIFIED");
        assertThat(audit.result()).isEqualTo("REJECT");
        assertThat(audit.errorCode()).isEqualTo("40107");
        assertThat(audit.clientIp()).isNotBlank();               // 审计三字段之一：IP
    }

    @Test
    void ENFORCED_正确APIKey_放行并落审计PASS含名称与方式() {
        stubFor(post(urlEqualTo("/echo")).willReturn(okJson("{\"ok\":true}")));
        String trace = nextTrace();
        var resp = callPost(outboundPath, "{}", trace, CLIENT, API_KEY);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        AccessAuthLogEntry audit = awaitAudit(trace);
        assertThat(audit).isNotNull();
        assertThat(audit.result()).isEqualTo("PASS");
        assertThat(audit.principalId()).isEqualTo(CLIENT);
        assertThat(audit.principalName()).isEqualTo("B3 测试调用方");   // 名称快照
        assertThat(audit.authMethod()).isEqualTo("API_KEY");            // 方式
        assertThat(audit.clientIp()).isNotBlank();
        assertThat(audit.authAdapterId()).isNotBlank();
    }

    @Test
    void ENFORCED_错误APIKey_401且审计REJECT40100() {
        String trace = nextTrace();
        var resp = callPost(outboundPath, "{}", trace, CLIENT, "wrong-key");
        assertThat(resp.getStatusCode().value()).isEqualTo(401);

        AccessAuthLogEntry audit = awaitAudit(trace);
        assertThat(audit).isNotNull();
        assertThat(audit.result()).isEqualTo("REJECT");
        assertThat(audit.errorCode()).isEqualTo("40100");
        assertThat(audit.principalName()).isEqualTo("B3 测试调用方");
    }

    @Test
    void ENFORCED_内部调用令牌_放行且审计为PLATFORM_SELF() {
        stubFor(post(urlEqualTo("/echo")).willReturn(okJson("{\"ok\":true}")));
        String trace = nextTrace();
        var resp = http.post().uri(outboundPath)
                .header(InternalCallToken.HEADER, internalCallToken.value())
                .header("X-Trace-Id", trace)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve().toEntity(String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        AccessAuthLogEntry audit = awaitAudit(trace);
        assertThat(audit).isNotNull();
        assertThat(audit.authMethod()).isEqualTo("PLATFORM_SELF");
        assertThat(audit.result()).isEqualTo("PASS");
    }

    @Test
    void 回调方向_审计direction为CALLBACK_且401xx回填REJECT() {
        String trace = nextTrace();
        // 回调方向：坏签名（无 CALLBACK 凭证）→ 链内验签 40100
        var resp = http.post().uri(callbackPath)
                .header("X-Timestamp", String.valueOf(System.currentTimeMillis() / 1000))
                .header("X-Partner-Signature", "deadbeef")
                .header("X-Trace-Id", trace)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"event_id\":\"evt-b3\"}")
                .retrieve().toEntity(String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);

        AccessAuthLogEntry audit = awaitAudit(trace);
        assertThat(audit).isNotNull();
        assertThat(audit.direction()).isEqualTo("CALLBACK");
        assertThat(audit.principalType()).isEqualTo("SUPPLIER");
        assertThat(audit.principalId()).isEqualTo(TEST_APP);
        assertThat(audit.principalName()).isEqualTo("B3 测试应用");
        assertThat(audit.authMethod()).isEqualTo("HMAC-SHA256");
        assertThat(audit.result()).isEqualTo("REJECT");       // 链内 401xx 回填（§9.2②）
        assertThat(audit.errorCode()).isEqualTo("40100");
    }

    // ---------- helpers ----------

    private org.springframework.http.ResponseEntity<String> callPost(String path, String body, String trace,
                                                                   String clientId, String apiKey) {
        var spec = http.post().uri(path).header("X-Trace-Id", trace)
                .contentType(MediaType.APPLICATION_JSON).body(body);
        if (clientId != null) {
            spec = spec.header("X-Client-Id", clientId);
        }
        if (apiKey != null) {
            spec = spec.header("X-Api-Key", apiKey);
        }
        return spec.retrieve().toEntity(String.class);
    }

    private String nextTrace() {
        return "ca-" + System.nanoTime();
    }

    private int outboundRequestCount(String trace) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM outbound_request WHERE trace_id = ?",
                Integer.class, trace);
        return n == null ? 0 : n;
    }

    /** 审计异步批量写：轮询等待（≤8s；每轮补一次 flushNow，避免等满 1000ms 批量窗口） */
    private AccessAuthLogEntry awaitAudit(String trace) {
        for (int i = 0; i < 40; i++) {
            List<AccessAuthLogEntry> rows = accessAuthLogRepository.findByTrace(trace);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            accessAuthLogWriter.flushNow();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private long ensureFixtures() {
        if (!appRepository.existsById(TEST_APP)) {
            appService.create(new AppRequest(TEST_APP, "B3 测试应用", null, null, null, null,
                    WM_BASE, null, null, null, null, "B3 集成测试"));
        }
        if (!appRepository.isEnabled(TEST_APP)) {
            appService.enable(TEST_APP);
        }
        return groupService.list(TEST_APP).stream()
                .filter(g -> TEST_GROUP.equals(g.name()))
                .findFirst().map(GroupResponse::id)
                .orElseGet(() -> groupService.create(new GroupRequest(TEST_APP, TEST_GROUP, 1)));
    }

    private String createApiKeyAdapter() {
        String id = "ADP-B3-KEY";
        if (adapterRepository.findById(id).isEmpty()) {
            adapterService.create(new AdapterRequest(id, "B3 调用方 API Key", "auth",
                    "ClientApiKeyVerifyAdapter", true, "1.0", "{}"));
        }
        return id;
    }

    private String createHmacAdapter() {
        String id = "ADP-B3-HMAC";
        if (adapterRepository.findById(id).isEmpty()) {
            // catalog 里 signatureAlgorithm / signatureHeader / timestampToleranceSeconds 均为**必填** params
            adapterService.create(new AdapterRequest(id, "B3 回调验签", "auth",
                    "HmacCallbackVerifyAdapter", true, "1.0",
                    "{\"signatureAlgorithm\":\"HMAC-SHA256\",\"signatureHeader\":\"X-Partner-Signature\","
                            + "\"timestampToleranceSeconds\":\"300\"}"));
        }
        return id;
    }

    private void createOutboundInterface(String code, String path, long groupId) {
        if (interfaceRepository.findByPath(path).isPresent()) {
            return;
        }
        InterfaceRequest req = new InterfaceRequest(code, code, "OUTBOUND", "POST", path,
                "JSON", "JSON", TEST_APP, groupId,
                "/echo", null, null, 3000, 0, "B3 集成测试", BigDecimal.ONE,
                List.of(new ParamDto("IN", "hello", "string", true, "world", 1)),
                List.of(new BodyDto("IN", "json", "{\"hello\":\"world\"}", null)),
                List.of(), List.of(),
                List.of(new BindingDto("MESSAGE", null, null), new BindingDto("AUTH", null, null)),
                List.of(), null);
        interfaceService.publish(interfaceService.create(req));
    }

    private void createInboundInterface(String code, String path, long groupId, String adapterId,
                                        String callbackUrl) {
        if (interfaceRepository.findByPath(path).isPresent()) {
            return;
        }
        InterfaceRequest req = new InterfaceRequest(code, code, "INBOUND", "POST", path,
                "JSON", "JSON", TEST_APP, groupId,
                null, callbackUrl, null, 3000, 0, "B3 集成测试", BigDecimal.ONE,
                List.of(new ParamDto("IN", "event_id", "string", true, "evt-b3", 1),
                        new ParamDto("OUT", "event_id", "string", true, null, 1)),
                List.of(new BodyDto("IN", "json", "{\"event_id\":\"evt-b3\"}", null)),
                List.of(), List.of(),
                List.of(new BindingDto("CALLBACK_AUTH", adapterId, null)),
                List.of(), null);
        interfaceService.publish(interfaceService.create(req));
    }


    // ---------- C3：接口级「入站鉴权方式」（CLIENT_AUTH） ----------

    @Test
    void 接口绑定CLIENT_AUTH_未配平台默认时靠绑定生效_改绑定即时生效_用例45与51b() {
        stubFor(post(urlEqualTo("/echo")).willReturn(okJson("{\"ok\":true}")));
        // 平台默认留空 + 开放集（免自报主体）⇒ 本次完全依赖「接口绑定」
        inboundAuthSettingService.save(null, false, "b3-test");
        com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView issued =
                inboundCredentialService.prepare("PLATFORM", null, "API_KEY", "接口绑定用例");
        inboundCredentialService.activate("PLATFORM", null, issued.id());

        // ⓪ 未绑定 + 无平台默认 ⇒ fail-closed 40108（不是放行；两级都没有）
        String t0 = nextTrace();
        assertThat(callPost(outboundPath, "{}", t0, null, issued.plaintext()).getStatusCode().value())
                .isEqualTo(401);
        assertThat(awaitAudit(t0).errorCode()).isEqualTo("40108");

        // ① 绑定 CLIENT_AUTH ⇒ 同一请求 200（**闸门每请求读绑定** ⇒ 改绑定即时生效，无需重启/不用等 TTL）
        Long interfaceId = interfaceRepository.findByPath(outboundPath).orElseThrow().id();
        jdbc.update("INSERT INTO interface_adapter_binding (interface_id, role, adapter_id, version) "
                + "VALUES (?, 'CLIENT_AUTH', ?, NULL)", interfaceId, createApiKeyAdapter());
        String t1 = nextTrace();
        assertThat(callPost(outboundPath, "{}", t1, null, issued.plaintext()).getStatusCode().value())
                .isEqualTo(200);
        assertThat(awaitAudit(t1).credentialLabel()).isEqualTo("接口绑定用例");

        // ② 解绑 ⇒ 立刻回到 40108（反证：绑定确实是热读的）
        jdbc.update("DELETE FROM interface_adapter_binding WHERE interface_id = ? AND role = 'CLIENT_AUTH'",
                interfaceId);
        String t2 = nextTrace();
        assertThat(callPost(outboundPath, "{}", t2, null, issued.plaintext()).getStatusCode().value())
                .isEqualTo(401);
        assertThat(awaitAudit(t2).errorCode()).isEqualTo("40108");
    }

    @Test
    void 绑定角色校验_入站回调接口不允许绑定CLIENT_AUTH() {
        long groupId = jdbc.queryForObject("SELECT id FROM app_group WHERE app_id = ? LIMIT 1",
                Long.class, TEST_APP);
        InterfaceRequest req = new InterfaceRequest("IF-CA-BAD", "错误绑定夹具", "INBOUND", "POST",
                "/ca/bad-binding", "JSON", "JSON", TEST_APP, groupId,
                null, WM_BASE + "/delivery-ok", null, 3000, 0, "C3 校验用例", BigDecimal.ONE,
                List.of(new ParamDto("IN", "event_id", "string", true, "evt-b3", 1),
                        new ParamDto("OUT", "event_id", "string", true, null, 1)),
                List.of(new BodyDto("IN", "json", "{\"event_id\":\"evt-b3\"}", null)),
                List.of(), List.of(),
                List.of(new BindingDto("CALLBACK_AUTH", createHmacAdapter(), null),
                        new BindingDto("CLIENT_AUTH", createApiKeyAdapter(), null)),
                List.of(), null);

        assertThatThrownBy(() -> interfaceService.create(req))
                .isInstanceOf(com.deepx.apicenter.exception.BizException.class)
                .hasMessageContaining("入站接口不允许绑定调用方鉴权（CLIENT_AUTH）");
    }

    /** 先清运行数据（接口有运行数据时禁止删除），再删接口/分组/应用/调用方；审计行按 trace 前缀清 */
    private void cleanFixtures() {
        jdbc.update("DELETE FROM outbound_request_state_log WHERE outbound_request_id IN "
                + "(SELECT id FROM outbound_request WHERE app_id = ?)", TEST_APP);
        jdbc.update("DELETE FROM outbound_request WHERE app_id = ?", TEST_APP);
        jdbc.update("DELETE FROM dead_letter WHERE biz_type = 'INBOUND' AND ref_id IN "
                + "(SELECT id FROM inbound_delivery WHERE app_id = ?)", TEST_APP);
        jdbc.update("DELETE FROM inbound_delivery WHERE app_id = ?", TEST_APP);
        jdbc.update("DELETE FROM dead_letter WHERE biz_type = 'OUTBOUND' AND ref_id IN "
                + "(SELECT id FROM outbound_request WHERE app_id = ?)", TEST_APP);
        for (String code : List.of("IF-CA-ECHO", "IF-CA-CB")) {
            jdbc.update("DELETE FROM interface_snapshot WHERE interface_id IN "
                    + "(SELECT id FROM interface WHERE code = ?)", code);
        }
        jdbc.update("DELETE FROM interface WHERE app_id = ?", TEST_APP);
        jdbc.update("DELETE FROM app_group WHERE app_id = ?", TEST_APP);
        jdbc.update("DELETE FROM app_credential WHERE app_id = ?", TEST_APP);
        if (clientAppRepository.existsById(CLIENT)) {
            clientService.delete(CLIENT);
        }
        for (String id : List.of("ADP-B3-KEY", "ADP-B3-HMAC")) {
            if (adapterRepository.findById(id).isPresent()) {
                adapterService.delete(id);   // 顺带清引用与链缓存（走服务而非裸 SQL）
            }
        }
        jdbc.update("DELETE FROM app WHERE app_id = ?", TEST_APP);
        jdbc.update("DELETE FROM access_auth_log WHERE trace_id LIKE 'ca-%'");
    }
}
