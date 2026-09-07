package com.deepx.apicenter;

import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
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
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 监控统计增强集成测试（仪表盘/监控 v0.2 保护网）：
 * call-logs 多维过滤 / stats/trend 分桶 / stats/top-interfaces 排行 /
 * outbound-requests/{id} 详情（payload 预览 + 审计）。WireMock 造一次 200 成功 + 一次 401 死信，
 * 产生 IN/OUT call_log 与出站终态后再断言聚合口径（宽松数值断言，防远程库时序抖动）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-fixed-delay-ms=3600000"
})
class MonitorStatsIntegrationTest {

    private static final String TEST_APP = "M4-STATS-APP";
    private static final int WM_PORT = 18080;
    private static WireMockServer wireMock;

    @LocalServerPort
    private int port;

    private final RestClient restClient = buildClient();

    private static RestClient buildClient() {
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
    private com.deepx.apicenter.repository.AppRepository appRepository;
    @Autowired
    private com.deepx.apicenter.repository.InterfaceRepository interfaceRepository;
    @Autowired
    private com.deepx.apicenter.repository.OutboundRequestRepository outboundRequestRepository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private long ifaceId;

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
        cleanup();
        appService.create(new AppRequest(TEST_APP, "监控统计测试", null, null, null, null,
                "http://localhost:" + WM_PORT, null, null, null, null, "v0.2 聚合测试"));
        appService.enable(TEST_APP);
        long groupId = groupService.create(new GroupRequest(TEST_APP, "组", 0));
        ifaceId = interfaceService.create(new InterfaceRequest(
                "IF-M4-STATS", "IF-M4-STATS", "OUTBOUND", "POST", "/m4/stats",
                "JSON", "JSON", TEST_APP, groupId, "/stats-echo", null, null,
                3000, 4, "聚合测试接口", 1, List.of(), List.of(), List.of(), List.of(), List.of()));
        interfaceService.publish(ifaceId);
    }

    @AfterEach
    void cleanup() {
        if (appRepository.existsById(TEST_APP)) {
            outboundRequestRepository.deleteByApp(TEST_APP);
            jdbcTemplate.queryForList("SELECT id FROM interface WHERE app_id = ?", Long.class, TEST_APP)
                    .forEach(interfaceRepository::deleteCascade);
            appRepository.deleteCascade(TEST_APP);
        }
    }

    @Test
    void 统计聚合_日志过滤_趋势与排行_详情() {
        // 1) 200 成功 + 2) 401 死信 → IN/OUT 日志与出站终态
        wireMock.resetAll();
        stubFor(post("/stats-echo").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
        assertThat(httpPost("/m4/stats", "{\"a\":1}")).isEqualTo(200);
        wireMock.resetAll();
        stubFor(post("/stats-echo").willReturn(aResponse().withStatus(401).withBody("denied")));
        assertThat(httpPost("/m4/stats", "{\"a\":2}")).isEqualTo(502); // 4xx→死信→平台 50201 信封（HTTP=业务码/100）

        // call-logs 多维过滤：方向 IN / 应用 / 2xx 结果组（按本次接口 id 过滤；call_log 异步落库 → 先等计数）
        waitFor("/monitor/call-logs?direction=IN&appId=" + TEST_APP + "&interfaceId=" + ifaceId, "\"total\":2");
        String in = getJson("/monitor/call-logs?direction=IN&appId=" + TEST_APP + "&interfaceId=" + ifaceId);
        assertThat(in).contains("\"direction\":\"IN\"").contains("\"total\":2");
        waitFor("/monitor/call-logs?direction=OUT&statusGroup=2xx&appId=" + TEST_APP
                + "&interfaceId=" + ifaceId, "\"total\":1");
        String out2xx = getJson("/monitor/call-logs?direction=OUT&statusGroup=2xx&appId=" + TEST_APP
                + "&interfaceId=" + ifaceId);
        assertThat(out2xx).contains("\"direction\":\"OUT\"").contains("\"total\":1");
        // traceId 精确过滤命中（取一条真实 OUT 日志 trace）
        String trace = traceOfFirstOut();
        assertThat(getJson("/monitor/call-logs?traceId=" + trace)).contains("\"total\":2"); // trace 贯穿 IN+OUT

        // trend：近 24h 30min 桶，IN 总调用 ≥2
        String trend = getJson("/monitor/stats/trend?range=24h");
        assertThat(trend).contains("\"range\":\"24h\"");
        long inTotal = sumBucket(trend, "inCalls");
        assertThat(inTotal).isGreaterThanOrEqualTo(2);

        // top-interfaces：命中 IF-M4-STATS 且 inCalls>0
        String top = getJson("/monitor/stats/top-interfaces?range=24h&limit=8");
        assertThat(top).contains("IF-M4-STATS").contains("\"inCalls\":2");
        // dead≥1（401 死信终态）在窗口内
        assertThat(top).contains("\"dead\":1");

        // 出站详情：payload 预览 + audits（SUCCESS 无审计）
        String list = getJson("/monitor/outbound-requests?status=SUCCESS&appId=" + TEST_APP);
        long rowId = firstId(list);
        String detail = getJson("/monitor/outbound-requests/" + rowId);
        assertThat(detail).contains("\"status\":\"SUCCESS\"").contains("\"audits\":[]")
                .contains("inPayloadPreview");
    }

    // ---------- helpers ----------

    /** call_log 异步写：轮询等待断言条件（≤8s）后再继续 */
    private void waitFor(String path, String expectedToken) {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (getJson(path).contains(expectedToken)) {
                return;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }


    private String traceOfFirstOut() {
        String body = getJson("/monitor/call-logs?direction=OUT&appId=" + TEST_APP + "&pageSize=1");
        int i = body.indexOf("\"traceId\":\"");
        if (i < 0) {
            return "";
        }
        int s = i + "\"traceId\":\"".length();
        return body.substring(s, body.indexOf('"', s));
    }

    private long sumBucket(String json, String key) {
        long sum = 0;
        int idx = 0;
        String needle = "\"" + key + "\":";
        while ((idx = json.indexOf(needle, idx)) >= 0) {
            int s = idx + needle.length();
            int e = json.indexOf(',', s);
            int e2 = json.indexOf('}', s);
            int end = e < 0 ? e2 : Math.min(e, e2);
            try {
                sum += Long.parseLong(json.substring(s, end).trim());
            } catch (NumberFormatException ignored) {
                // 空窗/非数字键跳过
            }
            idx = end;
        }
        return sum;
    }

    private long firstId(String json) {
        int i = json.indexOf("\"id\":");
        if (i < 0) {
            return 0;
        }
        int s = i + "\"id\":".length();
        int e = json.indexOf(',', s);
        return Long.parseLong(json.substring(s, e).trim());
    }

    private int httpPost(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> resp = restClient.post().uri("http://localhost:" + port + path)
                .headers(h -> h.addAll(headers)).body(body.getBytes(StandardCharsets.UTF_8))
                .retrieve().toEntity(byte[].class);
        return resp.getStatusCode().value();
    }

    private String getJson(String path) {
        ResponseEntity<byte[]> resp = restClient.get().uri("http://localhost:" + port + "/api/admin" + path)
                .retrieve().toEntity(byte[].class);
        return resp.getBody() == null ? "" : new String(resp.getBody(), StandardCharsets.UTF_8);
    }
}
