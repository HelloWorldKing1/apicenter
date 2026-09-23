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
    }

    // ---------- 用例 ----------

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
        assertThat(audit.principalType()).isEqualTo("CLIENT");
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
