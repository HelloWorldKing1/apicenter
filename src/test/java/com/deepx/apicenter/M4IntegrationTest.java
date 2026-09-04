package com.deepx.apicenter;

import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.engine.CircuitBreaker;
import com.deepx.apicenter.engine.CircuitBreakerRegistry;
import com.deepx.apicenter.model.AdapterRow;
import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AlertEventRepository;
import com.deepx.apicenter.repository.AlertRuleRepository;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.CallLogRepository;
import com.deepx.apicenter.repository.DeadLetterRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.repository.ReconcileAuditRepository;
import com.deepx.apicenter.service.AppService;
import com.deepx.apicenter.service.CredentialService;
import com.deepx.apicenter.service.GatewayGuard;
import com.deepx.apicenter.service.GroupService;
import com.deepx.apicenter.service.InterfaceService;
import com.deepx.apicenter.service.MonitorService;
import com.deepx.apicenter.worker.AlertWorker;
import com.deepx.apicenter.worker.CompensationWorker;
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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 集成测试（M4 计划 §4）：熔断 C1/C2、UNKNOWN 对账 C3、TTL 降级 C4、死信重放 C5、
 * call_log 双向 + 脱敏 + trace 贯穿 C6、告警触发与冷却 C7、限流配额 C8。
 * WireMock 扮演上游；经真实网关 HTTP 路径（RANDOM_PORT）。
 * 熔断参数调短（min-calls=5 / open-duration=1s / probes=1）；@BeforeEach 复位防既有用例累计污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.api-center.circuit.minimum-number-of-calls=5",
        "app.api-center.circuit.failure-rate-threshold=0.5",
        "app.api-center.circuit.sliding-window-seconds=10",
        "app.api-center.circuit.open-duration-seconds=1",
        "app.api-center.circuit.half-open-probes=1",
        // 后台调度轮询拉长到 1 小时：补偿 / 告警用例手动 scan()（确定性）
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-fixed-delay-ms=3600000"
})
class M4IntegrationTest {

    private static final String TEST_APP = "M4-TEST-APP";
    private static final String AUTH_APP = "M4-AUTH-APP";
    private static final String LIMIT_APP = "M4-LIMIT-APP";
    private static final int WM_PORT = 18080;
    private static final String WM_BASE = "http://localhost:" + WM_PORT;

    private static WireMockServer wireMock;

    @LocalServerPort
    private int port;

    /** 测试直调客户端：长读超时（超时用例 ~3s + 短重试）+ 禁用默认状态异常抛出（与 M3 同模式） */
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
    private CredentialService credentialService;
    @Autowired
    private AppRepository appRepository;
    @Autowired
    private AdapterRepository adapterRepository;
    @Autowired
    private InterfaceRepository interfaceRepository;
    @Autowired
    private OutboundRequestRepository outboundRequestRepository;
    @Autowired
    private ReconcileAuditRepository reconcileAuditRepository;
    @Autowired
    private DeadLetterRepository deadLetterRepository;
    @Autowired
    private AlertRuleRepository alertRuleRepository;
    @Autowired
    private AlertEventRepository alertEventRepository;
    @Autowired
    private CallLogRepository callLogRepository;
    @Autowired
    private CompensationWorker compensationWorker;
    @Autowired
    private AlertWorker alertWorker;
    @Autowired
    private MonitorService monitorService;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    private GatewayGuard gatewayGuard;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long groupId;
    private long breakerIfaceId; // C1/C2：maxRetries=2（正常失败约 0.8s+，短路即时——时序可区分）
    private long slowIfaceId;    // C3/C4：上游延迟 3.5s > 读超时 3s → UNKNOWN
    private long rejectIfaceId;  // C5：上游 404 → 死信
    private long authIfaceId;    // C6：Bearer 出站凭证（脱敏断言）
    private long limitIfaceId;   // C8：qps_limit=2 应用
    private long alertEventIdFloor; // C7：清理本用例新增告警事件
    private long deadLetterIdFloor;

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
    void setupTestConfig() {
        wireMock.resetAll();
        circuitBreakerRegistry.resetAll(); // 熔断状态复位：防同接口累计失败跨用例开闸污染断言
        gatewayGuard.reset();
        alertEventIdFloor = maxId("alert_event");
        deadLetterIdFloor = maxId("dead_letter");
        jdbcTemplate.update("DELETE FROM alert_rule WHERE name LIKE 'M4-%'");

        // ADP-101 Bearer（C6 脱敏断言；与种子 / M3 同 id 幂等）
        if (!adapterRepository.existsById("ADP-101")) {
            adapterRepository.insert(new AdapterRow("ADP-101", "Bearer Token", "auth",
                    "BearerTokenAuthAdapter", true, "1.0",
                    "{\"headerName\":\"Authorization\",\"prefix\":\"Bearer\"}", null, null));
        }

        if (!appRepository.existsById(TEST_APP)) {
            appService.create(new AppRequest(TEST_APP, "M4 测试供应商", null,
                    null, null, null, WM_BASE, null, null, null, null,
                    "M4 集成测试：熔断 / 对账 / 死信（Noop 鉴权）"));
            appService.enable(TEST_APP);
            groupId = groupService.create(new GroupRequest(TEST_APP, "测试分组", 0));
            breakerIfaceId = interfaceService.create(outboundInterface(TEST_APP, groupId, "IF-M4-CB",
                    "/m4/breaker", "/breaker-fail", 2));
            interfaceService.publish(breakerIfaceId);
            slowIfaceId = interfaceService.create(outboundInterface(TEST_APP, groupId, "IF-M4-SLOW",
                    "/m4/slow", "/slow-supplier", 0));
            interfaceService.publish(slowIfaceId);
            rejectIfaceId = interfaceService.create(outboundInterface(TEST_APP, groupId, "IF-M4-REJ",
                    "/m4/reject", "/reject-supplier", 0));
            interfaceService.publish(rejectIfaceId);
        } else {
            groupId = groupService.list(TEST_APP).get(0).id();
            breakerIfaceId = interfaceRepository.findByPath("/m4/breaker").orElseThrow().id();
            slowIfaceId = interfaceRepository.findByPath("/m4/slow").orElseThrow().id();
            rejectIfaceId = interfaceRepository.findByPath("/m4/reject").orElseThrow().id();
        }

        if (!appRepository.existsById(AUTH_APP)) {
            appService.create(new AppRequest(AUTH_APP, "M4 凭证脱敏供应商", null,
                    "ADP-101", null, null, WM_BASE, null, null, null, null,
                    "M4 集成测试：Bearer 出站凭证（call_log 脱敏断言）"));
            appService.enable(AUTH_APP);
            long g = groupService.create(new GroupRequest(AUTH_APP, "测试分组", 0));
            authIfaceId = interfaceService.create(outboundInterface(AUTH_APP, g, "IF-M4-AUTH",
                    "/m4/auth", "/echo-ok", 0));
            interfaceService.publish(authIfaceId);
            credentialService.update(AUTH_APP, new UpdateRequest("OUTBOUND", "m4-auth-secret-token-123456"));
        } else {
            authIfaceId = interfaceRepository.findByPath("/m4/auth").orElseThrow().id();
        }

        if (!appRepository.existsById(LIMIT_APP)) {
            appService.create(new AppRequest(LIMIT_APP, "M4 限流应用", null,
                    null, null, null, WM_BASE, null, null, 2, null,
                    "M4 集成测试：QPS 限流（qps_limit=2）"));
            appService.enable(LIMIT_APP);
            long g = groupService.create(new GroupRequest(LIMIT_APP, "测试分组", 0));
            limitIfaceId = interfaceService.create(outboundInterface(LIMIT_APP, g, "IF-M4-LIM",
                    "/m4/limit", "/echo-ok", 0));
            interfaceService.publish(limitIfaceId);
        } else {
            limitIfaceId = interfaceRepository.findByPath("/m4/limit").orElseThrow().id();
        }
    }

    @AfterEach
    void cleanup() {
        for (String app : List.of(TEST_APP, AUTH_APP, LIMIT_APP)) {
            outboundRequestRepository.deleteByApp(app);
            if (appRepository.existsById(app)) {
                jdbcTemplate.queryForList("SELECT id FROM interface WHERE app_id = ?", Long.class, app)
                        .forEach(interfaceRepository::deleteCascade);
                appRepository.deleteCascade(app);
            }
        }
        jdbcTemplate.update("DELETE FROM reconcile_audit WHERE outbound_request_id NOT IN (SELECT id FROM outbound_request)");
        jdbcTemplate.update("DELETE FROM alert_rule WHERE name LIKE 'M4-%'");
        jdbcTemplate.update("DELETE FROM alert_event WHERE id > ?", alertEventIdFloor);
        circuitBreakerRegistry.resetAll();
    }

    // ---------- C1/C2：熔断开闸 → 短路（不触达上游、不触发短重试）→ 半开恢复 ----------

    @Test
    void c1c2_持续5xx熔断开闸_短路不触达上游_半开探测恢复() throws InterruptedException {
        stubFor(post("/breaker-fail").willReturn(aResponse().withStatus(500)));

        // 5 次失败（每请求计一次失败；maxRetries=2 → 每请求 3 次上游调用）→ 窗口内 5/5 失败 ≥ 阈值 → OPEN
        for (int i = 0; i < 5; i++) {
            ResponseEntity<byte[]> resp = httpPost("/m4/breaker", "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(resp.getStatusCode().value()).isEqualTo(502);
            assertThat(body(resp)).contains("50201");
        }
        assertThat(circuitBreakerRegistry.stateOf(breakerIfaceId)).isEqualTo(CircuitBreaker.State.OPEN);

        // 5 请求 × 3（首送 + 2 短重试）= 15 次上游调用；其他缓存上下文的后台 worker 理论上可能
        // 重放历史记录（跨上下文共享 DB），只做下界采样
        long upstreamBefore = wireMock.countRequestsMatching(postRequestedFor(urlEqualTo("/breaker-fail")).build()).getCount();
        assertThat(upstreamBefore).isGreaterThanOrEqualTo(15);

        // 第 6 次：立即 50202 短路——不发起调用、不触发 @Retryable、不触达上游
        long start = System.currentTimeMillis();
        ResponseEntity<byte[]> shortCircuit = httpPost("/m4/breaker", "{}".getBytes(StandardCharsets.UTF_8));
        long elapsed = System.currentTimeMillis() - start;
        assertThat(shortCircuit.getStatusCode().value()).isEqualTo(502);
        assertThat(body(shortCircuit)).contains("50202");
        assertThat(elapsed).isLessThan(1500); // 正常失败链 ≈ 0.8s+（2 次退避 200+400ms + 3 次往返）；短路即时
        // 短路请求自身未触达上游：发送前后采样相等（采样窗口仅含本请求 ~100ms）
        assertThat(wireMock.countRequestsMatching(postRequestedFor(urlEqualTo("/breaker-fail")).build()).getCount())
                .isEqualTo(upstreamBefore);

        // 短路记录：COMPENSATING + 50202（转补偿不直接死信）
        OutboundRequestRow shortRow = latestOutbound(breakerIfaceId);
        assertThat(shortRow.status()).isEqualTo("COMPENSATING");
        assertThat(shortRow.errorCode()).isEqualTo("50202");

        // C2：上游恢复（stub 转 200）→ 冷却 1s 结束 → 半开探测成功（probes=1）→ CLOSED
        wireMock.resetAll();
        stubFor(post("/breaker-fail").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
        Thread.sleep(1500); // open-duration=1s + 缓冲（本上下文后台 worker 已关，半开探测即本次请求）
        ResponseEntity<byte[]> recovered = httpPost("/m4/breaker", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(recovered.getStatusCode().value()).isEqualTo(200);
        assertThat(body(recovered)).contains("\"code\":0");
        assertThat(circuitBreakerRegistry.stateOf(breakerIfaceId)).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ---------- C3：超时 UNKNOWN → 人工对账（SUCCESS / COMPENSATING）+ 审计 ----------

    @Test
    void c3_超时UNKNOWN_人工对账置位与审计留痕() {
        stubFor(post("/slow-supplier").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")
                .withFixedDelay(3500)));

        ResponseEntity<byte[]> resp = httpPost("/m4/slow", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(resp.getStatusCode().value()).isEqualTo(504);
        assertThat(body(resp)).contains("50401");
        OutboundRequestRow unknown = latestOutbound(slowIfaceId);
        assertThat(unknown.status()).isEqualTo("UNKNOWN");

        // ① 置为「已到达」→ SUCCESS + 审计 MANUAL
        ResponseEntity<byte[]> ok = postAdmin("/api/admin/monitor/outbound-requests/" + unknown.id() + "/reconcile",
                "{\"target\":\"SUCCESS\",\"operator\":\"m4-tester\",\"reason\":\"上游确认已到达\"}");
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(outboundRequestRepository.findById(unknown.id()).orElseThrow().status()).isEqualTo("SUCCESS");
        assertThat(reconcileAuditRepository.findByOutboundRequest(unknown.id())).hasSize(1);
        assertThat(reconcileAuditRepository.findByOutboundRequest(unknown.id()).get(0).source()).isEqualTo("MANUAL");
        assertThat(reconcileAuditRepository.findByOutboundRequest(unknown.id()).get(0).operator()).isEqualTo("m4-tester");

        // ② 再造一条 UNKNOWN → 置为「未到达」→ COMPENSATING 立即入队 → worker 重放成功
        ResponseEntity<byte[]> resp2 = httpPost("/m4/slow", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(resp2.getStatusCode().value()).isEqualTo(504);
        OutboundRequestRow unknown2 = latestOutbound(slowIfaceId);
        assertThat(unknown2.id()).isNotEqualTo(unknown.id());

        wireMock.resetAll(); // 上游恢复（无延迟）
        stubFor(post("/slow-supplier").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

        ResponseEntity<byte[]> requeue = postAdmin("/api/admin/monitor/outbound-requests/" + unknown2.id() + "/reconcile",
                "{\"target\":\"COMPENSATING\",\"operator\":\"m4-tester\",\"reason\":\"上游确认未到达\"}");
        assertThat(requeue.getStatusCode().value()).isEqualTo(200);
        assertThat(outboundRequestRepository.findById(unknown2.id()).orElseThrow().status())
                .isEqualTo("COMPENSATING");
        compensationWorker.scan(); // next_retry_at=now → 立即可扫（重放走 200 上游）
        assertThat(outboundRequestRepository.findById(unknown2.id()).orElseThrow().status()).isEqualTo("SUCCESS");
    }

    // ---------- C4：UNKNOWN TTL 自动降级 + 审计（source=TTL） ----------

    @Test
    void c4_UNKNOWN超TTL自动降级_COMPENSATING审计TTL() {
        stubFor(post("/slow-supplier").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")
                .withFixedDelay(3500)));
        ResponseEntity<byte[]> resp = httpPost("/m4/slow", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(resp.getStatusCode().value()).isEqualTo(504);
        OutboundRequestRow unknown = latestOutbound(slowIfaceId);
        assertThat(unknown.status()).isEqualTo("UNKNOWN");

        // 回拨 updated_at 11 分钟（unknown-ttl 默认 10 分钟）→ 降级扫描命中
        jdbcTemplate.update("UPDATE outbound_request SET updated_at = DATE_SUB(NOW(), INTERVAL 11 MINUTE) WHERE id = ?",
                unknown.id());
        monitorService.downgradeExpiredUnknown(); // 其他缓存上下文的后台 worker 理论上可能抢先降级，以落库结果断言
        OutboundRequestRow row = outboundRequestRepository.findById(unknown.id()).orElseThrow();
        assertThat(row.status()).isEqualTo("COMPENSATING");
        assertThat(row.errorCode()).isEqualTo("50401"); // 保留超时成因
        assertThat(reconcileAuditRepository.findByOutboundRequest(unknown.id()))
                .anySatisfy(a -> {
                    assertThat(a.source()).isEqualTo("TTL");
                    assertThat(a.operator()).isEqualTo("TTL-WORKER");
                });
    }

    // ---------- C5：死信重放（重新入队 → worker 自然重放成功 → HANDLED；已处理拒绝） ----------

    @Test
    void c5_死信重放_重新入队补偿成功_已处理拒绝() {
        stubFor(post("/reject-supplier").willReturn(aResponse().withStatus(404)));
        ResponseEntity<byte[]> resp = httpPost("/m4/reject", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(body(resp)).contains("50201");

        OutboundRequestRow dead = latestOutbound(rejectIfaceId);
        assertThat(dead.status()).isEqualTo("DEAD_LETTER");
        DeadLetterRepository.DeadLetterView deadLetter = findDeadLetter("OUTBOUND", dead.id());
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.status()).isEqualTo("PENDING");

        // 上游恢复（同路径改 200）→ 重放 = 重新入队（状态重置，复用既有 replay 路径）
        wireMock.resetAll();
        stubFor(post("/reject-supplier").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

        ResponseEntity<byte[]> replay = postAdmin("/api/admin/monitor/dead-letters/" + deadLetter.id() + "/replay", null);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        OutboundRequestRow requeued = outboundRequestRepository.findById(dead.id()).orElseThrow();
        assertThat(requeued.status()).isEqualTo("COMPENSATING");
        assertThat(requeued.attemptCount()).isZero(); // attempt 清零（防立即再转死信）
        assertThat(deadLetterRepository.findById(deadLetter.id()).orElseThrow().status()).isEqualTo("HANDLED");

        compensationWorker.scan(); // next_retry_at=now → 重放走 200 上游 → SUCCESS
        assertThat(outboundRequestRepository.findById(dead.id()).orElseThrow().status()).isEqualTo("SUCCESS");

        // 已处理（HANDLED）死信不可重复重放
        ResponseEntity<byte[]> again = postAdmin("/api/admin/monitor/dead-letters/" + deadLetter.id() + "/replay", null);
        assertThat(again.getStatusCode().value()).isEqualTo(400);
        assertThat(body(again)).contains("已处理");
    }

    // ---------- C6：call_log 双向 + 脱敏 + traceId 贯穿（与上游 X-Trace-Id 头） ----------

    @Test
    void c6_call_log双向落库_脱敏_traceId贯穿上游() {
        stubFor(post("/echo-ok").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

        HttpHeaders headers = jsonHeaders();
        headers.set("Authorization", "Bearer caller-token-abcdef123456"); // 调用方侧凭证（IN 条脱敏断言）
        headers.set("X-Trace-Id", "trace-m4-c6");
        ResponseEntity<byte[]> resp = restClient.post().uri(url("/m4/auth"))
                .headers(h -> h.addAll(headers))
                .body("{}".getBytes(StandardCharsets.UTF_8))
                .retrieve().toEntity(byte[].class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        // 上游收到 X-Trace-Id 公共头（M0-03 §1.2 透传，M4 补齐）
        wireMock.verify(postRequestedFor(urlEqualTo("/echo-ok"))
                .withHeader("X-Trace-Id", containing("trace-m4-c6")));
        // 出站请求携带 Bearer 凭证（脱敏只发生在 call_log，不影响真实请求）
        wireMock.verify(postRequestedFor(urlEqualTo("/echo-ok"))
                .withHeader("Authorization", containing("Bearer m4-auth-secret-token-123456")));

        // 异步批量写：轮询 call_log（1s flush 间隔）
        List<CallLogRepository.CallLogView> entries = awaitCallLogs("trace-m4-c6", 2);
        assertThat(entries).hasSize(2); // 恰两条：IN + OUT（切面在 @Retryable 之外，重试不逐次落日志）
        CallLogRepository.CallLogView in = entries.stream().filter(e -> "IN".equals(e.direction())).findFirst().orElseThrow();
        CallLogRepository.CallLogView out = entries.stream().filter(e -> "OUT".equals(e.direction())).findFirst().orElseThrow();
        assertThat(in.interfaceId()).isEqualTo(authIfaceId);
        assertThat(in.appId()).isEqualTo(AUTH_APP);
        assertThat(in.statusCode()).isEqualTo(200);
        assertThat(in.reqHeaders()).contains("Bear****") // 敏感头遮蔽（前4+****+后4）
                .doesNotContain("caller-token-abcdef123456"); // 原值不落库
        assertThat(out.interfaceId()).isEqualTo(authIfaceId);
        assertThat(out.appId()).isEqualTo(AUTH_APP);
        assertThat(out.url()).contains("/echo-ok");
        assertThat(out.reqHeaders()).contains("****").doesNotContain("m4-auth-secret-token-123456"); // 出站凭证已遮蔽
        assertThat(out.statusCode()).isEqualTo(200);
        assertThat(out.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    // ---------- C7：告警规则触发 + 冷却去重 ----------

    @Test
    void c7_告警规则触发_冷却期内不重复() {
        // 造一条 PENDING 死信（dead_letter_backlog > 0 阈值命中条件）
        stubFor(post("/reject-supplier").willReturn(aResponse().withStatus(404)));
        httpPost("/m4/reject", "{}".getBytes(StandardCharsets.UTF_8));

        alertRuleRepository.insert("M4-测试死信告警", "dead_letter_backlog", "> 0", null, true);
        Long ruleId = jdbcTemplate.queryForObject(
                "SELECT id FROM alert_rule WHERE name = 'M4-测试死信告警'", Long.class);

        alertWorker.scan(); // 规则缓存为空 → 本轮加载并评估 → 命中落库
        assertThat(countEventsOfRule(ruleId)).isGreaterThanOrEqualTo(1);

        long afterFirst = countEventsOfRule(ruleId);
        alertWorker.scan(); // 冷却期内（默认 5 分钟）同规则不重复
        assertThat(countEventsOfRule(ruleId)).isEqualTo(afterFirst);

        var events = alertEventRepository.findPaged(0, 10);
        assertThat(events.get(0).ruleId()).isNotNull();
        assertThat(events.get(0).metric()).isEqualTo("dead_letter_backlog");
        assertThat(events.get(0).level()).isEqualTo("CRITICAL");
    }

    // ---------- C8：QPS 限流拒绝不污染状态机 ----------

    @Test
    void c8_QPS限流_超限42901_不落运行表() {
        stubFor(post("/echo-ok").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

        long recordsBefore = countOutbound(LIMIT_APP);
        int passed = 0;
        int rejected = 0;
        // 6 连发（qps_limit=2，同秒窗口；即使跨秒边界也至多 2×2 通过 → 必有拒绝）
        for (int i = 0; i < 6; i++) {
            ResponseEntity<byte[]> resp = httpPost("/m4/limit", "{}".getBytes(StandardCharsets.UTF_8));
            if (resp.getStatusCode().value() == 429) {
                rejected++;
                assertThat(body(resp)).contains("42901");
            } else {
                assertThat(resp.getStatusCode().value()).isEqualTo(200);
                passed++;
            }
        }
        assertThat(rejected).isGreaterThanOrEqualTo(1);
        assertThat(passed).isLessThanOrEqualTo(4); // 至多两秒窗 × 2
        // 不污染状态机：运行表增量 = 放行数（拒绝发生在 createRecord 之前）
        assertThat(countOutbound(LIMIT_APP)).isEqualTo(recordsBefore + passed);
    }

    // ---------- helpers ----------

    private InterfaceRequest outboundInterface(String appId, long groupId, String code, String path,
                                               String upstreamPath, int maxRetries) {
        return new InterfaceRequest(code, code, "OUTBOUND", "POST", path,
                "JSON", "JSON", appId, groupId,
                upstreamPath, null, null, 3000, maxRetries, "M4 集成测试接口", 1,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<byte[]> httpPost(String path, byte[] body) {
        return restClient.post().uri(url(path))
                .headers(h -> h.addAll(jsonHeaders()))
                .body(body)
                .retrieve().toEntity(byte[].class);
    }

    private ResponseEntity<byte[]> postAdmin(String path, String json) {
        var spec = restClient.post().uri(url(path))
                .headers(h -> h.addAll(jsonHeaders()));
        if (json != null) {
            spec = spec.body(json.getBytes(StandardCharsets.UTF_8));
        }
        return spec.retrieve().toEntity(byte[].class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String body(ResponseEntity<byte[]> resp) {
        return resp.getBody() == null ? "" : new String(resp.getBody(), StandardCharsets.UTF_8);
    }

    private OutboundRequestRow latestOutbound(long interfaceId) {
        return jdbcList("SELECT * FROM outbound_request WHERE interface_id = ? ORDER BY id DESC LIMIT 1",
                interfaceId).get(0);
    }

    private List<OutboundRequestRow> jdbcList(String sql, Object... args) {
        return jdbcTemplate.query(sql, OutboundRequestRow.MAPPER, args);
    }

    private long countOutbound(String appId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbound_request WHERE app_id = ?", Long.class, appId);
        return n == null ? 0 : n;
    }

    private DeadLetterRepository.DeadLetterView findDeadLetter(String bizType, long refId) {
        return deadLetterRepository.findPaged(bizType, "PENDING", 0, 200).stream()
                .filter(d -> refId == (d.refId() == null ? -1 : d.refId()))
                .findFirst()
                .orElse(null);
    }

    /** 轮询异步 call_log（批量写 1s flush；上限 8s） */
    private List<CallLogRepository.CallLogView> awaitCallLogs(String traceId, int expected) {
        long deadline = System.currentTimeMillis() + 8000;
        List<CallLogRepository.CallLogView> entries = callLogRepository.findPaged(traceId, null, 0, 50);
        while (entries.size() < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            entries = callLogRepository.findPaged(traceId, null, 0, 50);
        }
        return entries;
    }

    /** 按规则维度计数告警事件（跨上下文后台 worker 理论上可能触发其他规则，隔离断言） */
    private long countEventsOfRule(long ruleId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert_event WHERE rule_id = ?", Long.class, ruleId);
        return n == null ? 0 : n;
    }

    private long maxId(String table) {
        Long n = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }
}
