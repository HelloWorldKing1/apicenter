package com.deepx.apicenter;

import com.deepx.apicenter.adapter.auth.HmacSigner;
import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.BodyDto;
import com.deepx.apicenter.dto.InterfaceDtos.FieldDefDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.MappingDto;
import com.deepx.apicenter.dto.InterfaceDtos.ParamDto;
import com.deepx.apicenter.model.AdapterRow;
import com.deepx.apicenter.model.InboundDeliveryRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InboundDeliveryRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.service.AppService;
import com.deepx.apicenter.service.CredentialService;
import com.deepx.apicenter.service.GroupService;
import com.deepx.apicenter.service.InterfaceService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 集成测试（M3 计划 §4）：入站回调 B1-B6 + 四场景出站 X1-X3 + 模拟回调端点，WireMock 扮演供应商与回调地址。
 * 经真实网关 HTTP 路径（RANDOM_PORT）——验签失败 401、裸 ack 出口、统一信封等接入层行为一并覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M3IntegrationTest {

    private static final String TEST_APP = "M3-TEST-APP";
    private static final String CALLBACK_SECRET = "m3-callback-secret";
    private static final int WM_PORT = 18080;
    private static final String WM_BASE = "http://localhost:" + WM_PORT;

    private static WireMockServer wireMock;

    @LocalServerPort
    private int port;

    /**
     * 测试直调客户端：长读超时（入站回调含短重试最坏 ~3s+，应用 Bean 的 3s 读超时会提前取消请求）
     * + defaultStatusHandler 禁用（4xx/5xx 原样返回由测试断言，与 M0-03 C1 同模式）。
     */
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
    private InboundDeliveryRepository inboundDeliveryRepository;
    @Autowired
    private OutboundRequestRepository outboundRequestRepository;
    @Autowired
    private CompensationWorker compensationWorker;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** XML 供应商响应（D-M3-1：顶层业务字段，ts 未声明专用于验证 RESP 过滤） */
    private static final String XML_RESPONSE = """
            <response><total>2</total><items>
            <item><id>1</id><name>demo</name></item>
            <item><id>2</id><name>xml</name></item>
            </items><ts>1690000000</ts></response>
            """;

    private long groupId;
    private long cbInterfaceId;   // B1-B5：JSON 回调接口
    private long cbXmlInterfaceId; // B6：XML 回调接口（protocol_in=XML，送达 JSON）

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().port(WM_PORT));
        wireMock.start();
        configureFor("localhost", WM_PORT);
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setupTestConfig() {
        wireMock.resetAll();
        stubFor(post("/delivery-ok").willReturn(aResponse().withStatus(200).withBody("{}")));
        // HMAC 回调验签实例（不依赖 seed 时机；与种子 ADP-301 同 id 幂等）
        if (!adapterRepository.existsById("ADP-301")) {
            adapterRepository.insert(new AdapterRow("ADP-301", "HMAC 回调验签", "auth",
                    "HmacCallbackVerifyAdapter", true, "1.0",
                    "{\"signatureAlgorithm\":\"HMAC-SHA256\",\"signatureHeader\":\"X-Partner-Signature\","
                            + "\"timestampToleranceSeconds\":\"300\",\"replayProtection\":false}",
                    null, null));
        }
        if (!appRepository.existsById(TEST_APP)) {
            appService.create(new AppRequest(TEST_APP, "M3 测试供应商", null,
                    null, "ADP-301", null, WM_BASE, null, null, null, null,
                    "M3 集成测试：HMAC 回调验签应用级默认 + WireMock 对端"));
            appService.enable(TEST_APP);
            credentialService.update(TEST_APP, new UpdateRequest("CALLBACK", CALLBACK_SECRET));
            groupId = groupService.create(new GroupRequest(TEST_APP, "测试分组", 0));
            cbInterfaceId = interfaceService.create(jsonCallbackInterface(groupId));
            interfaceService.publish(cbInterfaceId);
            cbXmlInterfaceId = interfaceService.create(xmlCallbackInterface(groupId));
            interfaceService.publish(cbXmlInterfaceId);
        } else {
            groupId = groupService.list(TEST_APP).get(0).id();
            cbInterfaceId = interfaceRepository.findByPath("/callback/m3/order").orElseThrow().id();
            cbXmlInterfaceId = interfaceRepository.findByPath("/callback/m3/order-xml").orElseThrow().id();
        }
    }

    @AfterEach
    void cleanup() {
        inboundDeliveryRepository.deleteByApp(TEST_APP);
        outboundRequestRepository.deleteByApp(TEST_APP);
        if (appRepository.existsById(TEST_APP)) {
            jdbcTemplate.queryForList("SELECT id FROM interface WHERE app_id = ?", Long.class, TEST_APP)
                    .forEach(interfaceRepository::deleteCascade);
            appRepository.deleteCascade(TEST_APP);
        }
    }

    // ---------- B1 正常闭环：验签 → 送达成功 → ACKED → ack 回执 ----------

    @Test
    void b1_正确签名回调_送达成功_ack回执() {
        byte[] body = "{\"event_id\":\"evt-1\",\"order_id\":\"ORD-1\"}".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<byte[]> resp = postCallback("/callback/m3/order", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String ack = new String(resp.getBody(), StandardCharsets.UTF_8);
        assertThat(ack).contains("\"returnCode\":0"); // ACK 字段名可配值固定
        assertThat(ack).contains("\"returnMsg\":\"success\"");
        // 送达成功 → ACKED；payload 快照为送达报文；ack_to_partner=ACKED（收到即回）
        InboundDeliveryRow row = latestDelivery();
        assertThat(row.deliveryStatus()).isEqualTo("ACKED");
        assertThat(row.ackToPartner()).isEqualTo("ACKED");
        assertThat(row.payload()).contains("\"order_id\":\"ORD-1\"");
        assertThat(row.callbackEventId()).isEqualTo("evt-1"); // 报文顶层 event_id 提取
        assertThat(row.callbackUrlSnapshot()).isEqualTo(WM_BASE + "/delivery-ok");
        // 送达确实发到回调地址（固定 POST）
        wireMock.verify(postRequestedFor(urlEqualTo("/delivery-ok")));
    }

    // ---------- B2 验签失败 / 过期时间戳 → 401 不落运行表 ----------

    @Test
    void b2_验签失败401_不落运行表() {
        byte[] body = "{\"event_id\":\"evt-2\",\"order_id\":\"ORD-2\"}".getBytes(StandardCharsets.UTF_8);
        int before = countDeliveries();

        // 错签名 → 401（40100）
        ResponseEntity<byte[]> bad = postCallbackRaw("/callback/m3/order", body,
                System.currentTimeMillis() / 1000 + "", "deadbeef");
        assertThat(bad.getStatusCode().value()).isEqualTo(401);
        assertThat(new String(bad.getBody(), StandardCharsets.UTF_8)).contains("40100");

        // 过期时间戳（超容差 300s）→ 401（40101）
        ResponseEntity<byte[]> expired = postCallbackRaw("/callback/m3/order", body,
                String.valueOf(System.currentTimeMillis() / 1000 - 600),
                HmacSigner.sign("HMAC-SHA256", CALLBACK_SECRET, String.valueOf(System.currentTimeMillis() / 1000 - 600), body));
        assertThat(expired.getStatusCode().value()).isEqualTo(401);
        assertThat(new String(expired.getBody(), StandardCharsets.UTF_8)).contains("40101");

        assertThat(countDeliveries()).isEqualTo(before); // 不落运行表（D7）
    }

    // ---------- B3 送达 5xx → PENDING → 补偿 worker 重送 → ACKED ----------

    @Test
    void b3_送达5xx_短重试耗尽PENDING_worker重送成功() {
        // 首期：回调地址 500（maxRetries=1 的接口 → 首送 + 1 短重试）
        wireMock.resetAll();
        stubFor(post("/delivery-flaky-500").willReturn(aResponse().withStatus(500)));
        updateCallbackUrl(cbInterfaceId, "/delivery-flaky-500");
        byte[] body = "{\"event_id\":\"evt-3\",\"order_id\":\"ORD-3\"}".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<byte[]> resp = postCallback("/callback/m3/order", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200); // ack 与送达解耦：仍回 ack
        InboundDeliveryRow row = latestDelivery();
        assertThat(row.deliveryStatus()).isEqualTo("PENDING");
        assertThat(row.attemptCount()).isEqualTo(1);

        // 回调地址恢复 → worker 重送 → ACKED
        wireMock.resetAll();
        stubFor(post("/delivery-flaky-500").willReturn(aResponse().withStatus(200).withBody("{}")));
        forceDue(row.id());
        compensationWorker.scan();
        assertThat(latestDelivery().deliveryStatus()).isEqualTo("ACKED");
        // attempt ≥ 2：手动扫描与 @Scheduled 调度扫描可能并发重送（重送幂等，按 ADR5 由调用方去重），不锁精确值
        assertThat(latestDelivery().attemptCount()).isGreaterThanOrEqualTo(2);
    }

    // ---------- B4 持续失败 → 重送耗尽 → DEAD_LETTER + 死信落库 ----------

    @Test
    void b4_持续失败_重送耗尽_死信落库() {
        wireMock.resetAll();
        stubFor(post("/delivery-fail").willReturn(aResponse().withStatus(500)));
        updateCallbackUrl(cbInterfaceId, "/delivery-fail");
        byte[] body = "{\"event_id\":\"evt-4\",\"order_id\":\"ORD-4\"}".getBytes(StandardCharsets.UTF_8);

        postCallback("/callback/m3/order", body);
        InboundDeliveryRow row = latestDelivery();
        assertThat(row.deliveryStatus()).isEqualTo("PENDING");
        assertThat(row.maxAttempts()).isEqualTo(5); // max_retries + 1（默认 4）

        // 逐次强制到期扫描：attempt 2/3/4/5 失败后，attempt=5 ≥ max → 死信
        for (int i = 0; i < 5; i++) {
            forceDue(row.id());
            compensationWorker.scan();
        }
        InboundDeliveryRow after = latestDelivery();
        assertThat(after.deliveryStatus()).isEqualTo("DEAD_LETTER");
        Integer dead = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dead_letter WHERE biz_type = 'INBOUND' AND ref_id = ?",
                Integer.class, after.id());
        assertThat(dead).isEqualTo(1);
    }

    // ---------- B5 重送按 callback_url_snapshot（不随接口改址漂移） ----------

    @Test
    void b5_重送按快照地址_不随接口改址漂移() {
        wireMock.resetAll();
        stubFor(post("/delivery-old").willReturn(aResponse().withStatus(500)));
        updateCallbackUrl(cbInterfaceId, "/delivery-old");
        byte[] body = "{\"event_id\":\"evt-5\",\"order_id\":\"ORD-5\"}".getBytes(StandardCharsets.UTF_8);

        postCallback("/callback/m3/order", body);
        InboundDeliveryRow row = latestDelivery();
        assertThat(row.deliveryStatus()).isEqualTo("PENDING");

        // 重送期间把接口回调地址改成新的可达地址（旧快照地址 stub 保持 500——否则 WireMock 404 会走「4xx 直接死信」分支）
        wireMock.resetAll();
        stubFor(post("/delivery-old").willReturn(aResponse().withStatus(500)));
        stubFor(post("/delivery-new").willReturn(aResponse().withStatus(200).withBody("{}")));
        updateCallbackUrl(cbInterfaceId, "/delivery-new");

        forceDue(row.id());
        compensationWorker.scan();

        InboundDeliveryRow after = latestDelivery();
        assertThat(after.callbackUrlSnapshot()).isEqualTo(WM_BASE + "/delivery-old"); // 快照不变
        assertThat(after.deliveryStatus()).isEqualTo("PENDING"); // 重送仍打到旧地址（500）
        // 新地址未收到任何送达
        wireMock.verify(0, postRequestedFor(urlEqualTo("/delivery-new")));
    }

    // ---------- B6 XML 回调 → 送达 JSON（入站交叉场景） ----------

    @Test
    void b6_xml回调_送达json_ack按protocol_in渲染() {
        byte[] body = "<request><event_id>evt-x</event_id><order_id>ORD-X</order_id></request>"
                .getBytes(StandardCharsets.UTF_8);

        ResponseEntity<byte[]> resp = postCallback("/callback/m3/order-xml", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String ack = new String(resp.getBody(), StandardCharsets.UTF_8);
        assertThat(ack).contains("<response>"); // XML 回调 → XML ack
        assertThat(ack).contains("<returnCode>0</returnCode>");
        InboundDeliveryRow row = latestDelivery();
        assertThat(row.deliveryStatus()).isEqualTo("ACKED");
        assertThat(row.callbackEventId()).isEqualTo("evt-x");
        // 送达报文为 JSON（protocol_out=JSON）
        wireMock.verify(postRequestedFor(urlEqualTo("/delivery-ok"))
                .withHeader("Content-Type", containing("application/json")));
    }

    // ---------- X1 json-xml 出站 + RESP 白名单过滤 ----------

    @Test
    void x1_jsonXml出站_resp过滤生效() {
        stubFor(post("/xml-supplier").willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/xml").withBody(XML_RESPONSE)));
        long x1 = interfaceService.create(outboundInterface(groupId, "IF-M3-X1", "/m3/x1",
                "JSON", "XML", "/xml-supplier"));
        interfaceService.publish(x1);

        ResponseEntity<byte[]> resp = httpPost("/m3/x1",
                "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8), jsonHeaders());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode envelope = parse(resp.getBody());
        assertThat(envelope.get("code").asInt()).isZero();
        JsonNode data = envelope.get("data");
        assertThat(data.get("total").asInt()).isEqualTo(2); // XML STRING → number 解析
        // items 单次出现为对象，内部同名 item 合并为数组（D-M3-1 同名合并语义）
        assertThat(data.get("items").get("item").size()).isEqualTo(2);
        assertThat(data.has("ts")).isFalse(); // 未声明字段过滤
        // 出站方向：平台发 XML 到供应商（Content-Type application/xml + 约定根元素 request）
        wireMock.verify(postRequestedFor(urlEqualTo("/xml-supplier"))
                .withHeader("Content-Type", containing("application/xml"))
                .withRequestBody(containing("<request>")));
    }

    // ---------- X2 xml-xml 出站 ----------

    @Test
    void x2_xmlXml出站_端到端成功() {
        stubFor(post("/xml-supplier").willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/xml").withBody(XML_RESPONSE)));
        long x2 = interfaceService.create(outboundInterface(groupId, "IF-M3-X2", "/m3/x2",
                "XML", "XML", "/xml-supplier"));
        interfaceService.publish(x2);

        ResponseEntity<byte[]> resp = httpPost("/m3/x2",
                "<request><a>1</a></request>".getBytes(StandardCharsets.UTF_8), xmlHeaders());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode envelope = parse(resp.getBody());
        assertThat(envelope.get("code").asInt()).isZero();
        assertThat(envelope.get("data").get("total").asInt()).isEqualTo(2);
    }

    // ---------- X3 xml-json 出站 ----------

    @Test
    void x3_xmlJson出站_端到端成功() {
        stubFor(post("/json-supplier").willReturn(okJson("""
                {"total": 5, "items": [{"id": 1}, {"id": 2}]}
                """)));
        long x3 = interfaceService.create(outboundInterface(groupId, "IF-M3-X3", "/m3/x3",
                "XML", "JSON", "/json-supplier"));
        interfaceService.publish(x3);

        ResponseEntity<byte[]> resp = httpPost("/m3/x3",
                "<request><a>1</a></request>".getBytes(StandardCharsets.UTF_8), xmlHeaders());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode envelope = parse(resp.getBody());
        assertThat(envelope.get("code").asInt()).isZero();
        assertThat(envelope.get("data").get("total").asInt()).isEqualTo(5); // JSON 响应 INT 兼容 number
    }

    // ---------- 模拟回调端点（管理面测试工具） ----------

    @Test
    void 模拟回调端点_签名正确走真实网关路径_返回ack与送达状态() {
        byte[] body = "{\"event_id\":\"evt-admin\",\"order_id\":\"ORD-A\"}".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<byte[]> resp = httpPost(
                "/api/admin/interfaces/" + cbInterfaceId + "/test-callback", body, jsonHeaders());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode envelope = parse(resp.getBody());
        assertThat(envelope.get("code").asInt()).isZero();
        JsonNode data = envelope.get("data");
        assertThat(data.get("ackStatus").asInt()).isEqualTo(200);
        assertThat(data.get("deliveryStatus").asText()).isEqualTo("ACKED");
        assertThat(data.get("ackBody").asText()).contains("\"returnCode\":0");
    }

    @Test
    void 模拟回调端点_出站接口拒绝() {
        long x1 = interfaceService.create(outboundInterface(groupId, "IF-M3-X1-ADMIN", "/m3/x1-admin",
                "JSON", "XML", "/xml-supplier"));
        interfaceService.publish(x1);

        ResponseEntity<byte[]> resp = httpPost(
                "/api/admin/interfaces/" + x1 + "/test-callback", "{}".getBytes(StandardCharsets.UTF_8), jsonHeaders());

        assertThat(resp.getStatusCode().value()).isEqualTo(400); // 40001 仅 INBOUND
        assertThat(new String(resp.getBody(), StandardCharsets.UTF_8)).contains("仅适用于入站回调接口");
    }

    // ---------- helpers ----------

    private InterfaceRequest jsonCallbackInterface(long groupId) {
        List<ParamDto> in = List.of(
                new ParamDto("IN", "event_id", "string", true, "evt-1", 1),
                new ParamDto("IN", "order_id", "string", true, "ORD-1", 2));
        List<ParamDto> out = List.of(
                new ParamDto("OUT", "event_id", "string", true, null, 1),
                new ParamDto("OUT", "order_id", "string", true, null, 2));
        return new InterfaceRequest("IF-M3-CB", "M3 回调演示", "INBOUND", "POST", "/callback/m3/order",
                "JSON", "JSON", TEST_APP, groupId,
                null, WM_BASE + "/delivery-ok", null, 3000, 4, "M3 集成测试 JSON 回调接口", 1,
                java.util.stream.Stream.concat(in.stream(), out.stream()).toList(),
                List.of(new BodyDto("IN", "json", "{\"event_id\":\"evt-1\",\"order_id\":\"ORD-1\"}", null)),
                List.of(new MappingDto("event_id", "rename", "event_id", null, null, 1),
                        new MappingDto("order_id", "rename", "order_id", null, null, 2)),
                List.of(new FieldDefDto("ACK", "returnCode", "number", "回执码", 1),
                        new FieldDefDto("ACK", "returnMsg", "string", "回执消息", 2)),
                List.of(new BindingDto("CALLBACK_AUTH", null, null))); // 显式继承应用默认 ADP-301
    }

    private InterfaceRequest xmlCallbackInterface(long groupId) {
        List<ParamDto> in = List.of(
                new ParamDto("IN", "event_id", "string", true, "evt-x", 1),
                new ParamDto("IN", "order_id", "string", true, "ORD-X", 2));
        List<ParamDto> out = List.of(
                new ParamDto("OUT", "event_id", "string", true, null, 1),
                new ParamDto("OUT", "order_id", "string", true, null, 2));
        return new InterfaceRequest("IF-M3-CB-XML", "M3 XML 回调演示", "INBOUND", "POST", "/callback/m3/order-xml",
                "XML", "JSON", TEST_APP, groupId,
                null, WM_BASE + "/delivery-ok", null, 3000, 4, "M3 集成测试 XML 回调接口（B6）", 1,
                java.util.stream.Stream.concat(in.stream(), out.stream()).toList(),
                List.of(new BodyDto("IN", "json", "<request><event_id>evt-x</event_id></request>", null)),
                List.of(new MappingDto("event_id", "rename", "event_id", null, null, 1),
                        new MappingDto("order_id", "rename", "order_id", null, null, 2)),
                List.of(new FieldDefDto("ACK", "returnCode", "number", "回执码", 1),
                        new FieldDefDto("ACK", "returnMsg", "string", "回执消息", 2)),
                List.of(new BindingDto("CALLBACK_AUTH", null, null)));
    }

    private InterfaceRequest outboundInterface(long groupId, String code, String path,
                                               String protocolIn, String protocolOut, String upstreamPath) {
        return new InterfaceRequest(code, code, "OUTBOUND", "POST", path,
                protocolIn, protocolOut, TEST_APP, groupId,
                upstreamPath, null, null, 3000, 4, "M3 四场景测试接口", 1,
                List.of(), List.of(), List.of(),
                List.of(new FieldDefDto("RESP", "total", "number", "总数", 1),
                        new FieldDefDto("RESP", "items", "array", "列表", 2)),
                List.of());
    }

    private ResponseEntity<byte[]> postCallback(String path, byte[] body) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        return postCallbackRaw(path, body, ts, HmacSigner.sign("HMAC-SHA256", CALLBACK_SECRET, ts, body));
    }

    private ResponseEntity<byte[]> postCallbackRaw(String path, byte[] body, String timestamp, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Timestamp", timestamp);
        headers.set("X-Partner-Signature", signature);
        headers.set("X-Trace-Id", "trace-m3");
        return httpPost(path, body, headers);
    }

    /** 直调本服务 HTTP POST（RestClient 直调，与 M0-03 C1 契约同模式；4xx/5xx 原样返回由测试断言） */
    private ResponseEntity<byte[]> httpPost(String path, byte[] body, HttpHeaders headers) {
        return restClient.post()
                .uri("http://localhost:" + port + path)
                .headers(h -> headers.forEach(h::addAll))
                .body(body)
                .retrieve()
                .toEntity(byte[].class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders xmlHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return headers;
    }

    private InboundDeliveryRow latestDelivery() {
        return inboundDeliveryRepository.findByTrace("trace-m3").stream().findFirst()
                .orElseGet(() -> jdbcTemplate.query("""
                        SELECT * FROM inbound_delivery WHERE app_id = ? ORDER BY id DESC LIMIT 1
                        """, InboundDeliveryRow.MAPPER, TEST_APP).get(0));
    }

    private int countDeliveries() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inbound_delivery WHERE app_id = ?", Integer.class, TEST_APP);
        return n == null ? 0 : n;
    }

    private void forceDue(long deliveryId) {
        jdbcTemplate.update(
                "UPDATE inbound_delivery SET next_retry_at = NOW() - INTERVAL 1 SECOND WHERE id = ?", deliveryId);
    }

    private void updateCallbackUrl(long interfaceId, String path) {
        InterfaceRow row = interfaceRepository.findById(interfaceId).orElseThrow();
        // 更新回调地址（快照语义验证：旧 PENDING 记录仍按旧地址重送）
        interfaceService.update(interfaceId, new InterfaceRequest(row.code(), row.name(), "INBOUND", "POST",
                row.path(), "JSON", "JSON", TEST_APP, row.groupId(),
                null, WM_BASE + path, null, row.timeoutMs(), row.maxRetries(), row.desc(), row.version(),
                interfaceRepository.findParams(interfaceId).stream()
                        .map(p -> new ParamDto(p.side(), p.name(), p.type(), true, null, 0)).toList(),
                List.of(),
                interfaceRepository.findMappings(interfaceId).stream()
                        .map(m -> new MappingDto(m.source(), m.op(), m.target(), m.param(), m.nullStrategy(), m.sortOrder()))
                        .toList(),
                List.of(new FieldDefDto("ACK", "returnCode", "number", "回执码", 1),
                        new FieldDefDto("ACK", "returnMsg", "string", "回执消息", 2)),
                List.of(new BindingDto("CALLBACK_AUTH", null, null))));
    }

    private JsonNode parse(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应解析失败：" + new String(body, StandardCharsets.UTF_8), e);
        }
    }
}
