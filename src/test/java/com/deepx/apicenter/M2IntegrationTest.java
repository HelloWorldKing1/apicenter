package com.deepx.apicenter;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.FieldDefDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.ParamDto;
import com.deepx.apicenter.engine.OutboundEngine;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdapterRow;
import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AppRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2 集成测试：fastmoss 黄金用例 G1-G4 端到端（开发计划 §2.3，mock 对端先行）。
 * WireMock 模拟上游供应商；真实 fastmoss 联调在 M2 后按开发计划「联调验收」执行。
 * 覆盖：链执行（Bearer 鉴权 + JSON 解码 + 信封适配）+ 出站状态机全分支。
 */
@SpringBootTest
class M2IntegrationTest {

    private static final String TEST_APP = "M2-TEST-APP";
    private static final int WM_PORT = 18080;
    private static final String WM_BASE = "http://localhost:" + WM_PORT;

    private static WireMockServer wireMock;

    @Autowired
    private OutboundEngine outboundEngine;
    @Autowired
    private AppService appService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private InterfaceService interfaceService;
    @Autowired
    private CredentialService credentialService;
    @Autowired
    private AdapterRepository adapterRepository;
    @Autowired
    private AppRepository appRepository;
    @Autowired
    private InterfaceRepository interfaceRepository;
    @Autowired
    private OutboundRequestRepository outboundRequestRepository;
    @Autowired
    private CompensationWorker compensationWorker;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 黄金用例响应样例（开发计划 §2.4，简版但保留断言字段） */
    private static final String GOLDEN_RESPONSE = """
            {
              "code": 0,
              "data": {
                "total": 822,
                "list": [
                  {
                    "seller_id": "7494312521977267257",
                    "uid": "6682898641256350725",
                    "unique_id": "megandd1",
                    "nickname": "megan!",
                    "region": "US",
                    "category_id": 4,
                    "units_sold": 1135,
                    "gmv": 70283.55
                  }
                ]
              },
              "message": "",
              "timestamp": 1788252017,
              "request_id": "b83de1e8-88b9-fc53-28a5-1f4872029fea"
            }
            """;

    /** 黄金用例请求体（开发计划 §2.4） */
    private static final String GOLDEN_REQUEST = """
            {
              "filter": { "seller_id": "7494312521977267257" },
              "orderby": [ { "field": "units_sold", "order": "desc" } ],
              "page": 1,
              "pagesize": 1
            }
            """;

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
        // 清空 WireMock stub 与请求计数（跨测试隔离）
        wireMock.resetAll();
        // 确保平台默认与黄金用例适配器存在（seed 可能已导入，幂等）
        insertAdapterIfAbsent("ADP-101", "Bearer Token", "auth", "BearerTokenAuthAdapter",
                "{\"headerName\":\"Authorization\",\"prefix\":\"Bearer\"}");
        insertAdapterIfAbsent("ADP-201", "信封报文适配", "message", "EnvelopeMessageAdapter",
                "{\"envelope\":\"data\",\"codeField\":\"code\",\"successValue\":\"0\",\"messageField\":\"message\"}");
        if (!appRepository.existsById(TEST_APP)) {
            appService.create(new AppRequest(TEST_APP, "M2 测试供应商", null,
                    "ADP-101", null, "ADP-201", WM_BASE, null, null, null, null, "WireMock 模拟对端"));
            appService.enable(TEST_APP);
            credentialService.update(TEST_APP, new UpdateRequest("OUTBOUND", "m2-golden-token"));
            long groupId = groupService.create(new GroupRequest(TEST_APP, "测试分组", 0));
            long ifaceId = interfaceService.create(goldenInterface(groupId, 1));
            interfaceService.publish(ifaceId);
        }
    }

    @AfterEach
    void cleanup() {
        outboundRequestRepository.deleteByApp(TEST_APP);
        if (appRepository.existsById(TEST_APP)) {
            jdbcTemplate.queryForList("SELECT id FROM interface WHERE app_id = ?", Long.class, TEST_APP)
                    .forEach(interfaceRepository::deleteCascade);
            appRepository.deleteCascade(TEST_APP);
        }
    }

    // ---------- G1 正常调用 ----------

    @Test
    void g1_正常调用_信封适配_状态SUCCESS() {
        stubFor(post("/shop/v1/creatorList").willReturn(okJson(GOLDEN_RESPONSE)));

        ApiResult<?> result = outboundEngine.dispatch("/test/m2/golden", "POST",
                GOLDEN_REQUEST.getBytes(StandardCharsets.UTF_8), "biz-g1", "trace-g1");

        assertThat(result.code()).isZero();
        JsonNode data = (JsonNode) result.data();
        assertThat(data.get("total").asInt()).isEqualTo(822);
        assertThat(data.get("list").get(0).get("nickname").asText()).isEqualTo("megan!");
        assertThat(data.get("list").get(0).get("units_sold").asInt()).isEqualTo(1135);
        // 状态机落 SUCCESS
        OutboundRequestRow row = outboundRequestRepository.findByBizId(TEST_APP, "biz-g1").get(0);
        assertThat(row.status()).isEqualTo("SUCCESS");
        assertThat(row.attemptCount()).isEqualTo(1);
        // 凭证头被附加（Bearer 出站鉴权生效）
        wireMock.verify(postRequestedFor(urlEqualTo("/shop/v1/creatorList"))
                .withHeader("Authorization", equalTo("Bearer m2-golden-token")));
    }

    // ---------- G2 上游 4xx → 死信 ----------

    @Test
    void g2_上游401_落死信() {
        stubFor(post("/shop/v1/creatorList").willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> outboundEngine.dispatch("/test/m2/golden", "POST",
                GOLDEN_REQUEST.getBytes(StandardCharsets.UTF_8), "biz-g2", "trace-g2"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("死信");

        OutboundRequestRow row = outboundRequestRepository.findByBizId(TEST_APP, "biz-g2").get(0);
        assertThat(row.status()).isEqualTo("DEAD_LETTER");
        Integer dead = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dead_letter WHERE ref_id = ?", Integer.class, row.id());
        assertThat(dead).isEqualTo(1);
    }

    // ---------- G3 上游 5xx → 短重试耗尽 → 补偿 worker 兜底 ----------

    @Test
    void g3_上游5xx_重试耗尽补偿_worker重放成功() {
        stubFor(post("/shop/v1/creatorList").willReturn(aResponse().withStatus(500)));

        // maxRetries=1 → 首送 + 1 次重试（共 2 次调用）后耗尽 → COMPENSATING
        assertThatThrownBy(() -> outboundEngine.dispatch("/test/m2/golden", "POST",
                GOLDEN_REQUEST.getBytes(StandardCharsets.UTF_8), "biz-g3", "trace-g3"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("补偿队列");
        OutboundRequestRow row = outboundRequestRepository.findByBizId(TEST_APP, "biz-g3").get(0);
        assertThat(row.status()).isEqualTo("COMPENSATING");
        wireMock.verify(2, postRequestedFor(urlEqualTo("/shop/v1/creatorList"))); // 首送 + 1 次短重试

        // 上游恢复 → 补偿 worker 重放 → SUCCESS
        stubFor(post("/shop/v1/creatorList").willReturn(okJson(GOLDEN_RESPONSE)));
        jdbcTemplate.update("UPDATE outbound_request SET next_retry_at = NOW() - INTERVAL 1 SECOND WHERE id = ?", row.id());
        compensationWorker.scan();
        OutboundRequestRow after = outboundRequestRepository.findByBizId(TEST_APP, "biz-g3").get(0);
        assertThat(after.status()).isEqualTo("SUCCESS");
    }

    // ---------- G4 上游超时 → UNKNOWN 对账 ----------

    @Test
    void g4_上游超时_UNKNOWN待对账() {
        // 全局读超时 3000ms；stub 延迟 3500ms → 读超时（ResourceAccessException）→ UNKNOWN
        stubFor(post("/shop/v1/creatorList")
                .willReturn(okJson(GOLDEN_RESPONSE).withFixedDelay(3500)));

        // maxRetries=0 的接口 → 首送即耗尽
        long groupId = groupService.list(TEST_APP).get(0).id();
        long ifaceId = interfaceService.create(goldenInterface(groupId, 0));
        interfaceService.publish(ifaceId);

        assertThatThrownBy(() -> outboundEngine.dispatch("/test/m2/golden-no-retry", "POST",
                GOLDEN_REQUEST.getBytes(StandardCharsets.UTF_8), "biz-g4", "trace-g4"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("UNKNOWN");
        OutboundRequestRow row = outboundRequestRepository.findByBizId(TEST_APP, "biz-g4").get(0);
        assertThat(row.status()).isEqualTo("UNKNOWN");
        assertThat(row.errorCode()).isEqualTo("50401");
    }

    // ---------- 接口绑定为空 → 继承应用默认出站鉴权（绑定解析契约回归） ----------

    @Test
    void 接口绑定为空_继承应用默认出站鉴权() {
        stubFor(post("/shop/v1/creatorList").willReturn(okJson(GOLDEN_RESPONSE)));
        long groupId = groupService.list(TEST_APP).get(0).id();
        // 管理面保存形态：绑定行存在但 adapter_id 为空（显式继承应用默认 ADP-101）
        long ifaceId = interfaceService.create(goldenInterface(groupId, 1, true));
        interfaceService.publish(ifaceId);

        ApiResult<?> result = outboundEngine.dispatch("/test/m2/golden", "POST",
                GOLDEN_REQUEST.getBytes(StandardCharsets.UTF_8), "biz-inherit", "trace-inherit");

        assertThat(result.code()).isZero();
        // 应用默认 ADP-101（Bearer）生效：WireMock 收到的请求带凭证头
        wireMock.verify(postRequestedFor(urlEqualTo("/shop/v1/creatorList"))
                .withHeader("Authorization", equalTo("Bearer m2-golden-token")));
    }

    // ---------- 补偿重放超时 → UNKNOWN 对账（高危 #2 修复验证） ----------

    @Test
    void 补偿重放遇超时_转UNKNOWN而非盲目重试() {
        stubFor(post("/shop/v1/creatorList").willReturn(okJson(GOLDEN_RESPONSE).withFixedDelay(3500)));
        long groupId = groupService.list(TEST_APP).get(0).id();
        long ifaceId = interfaceService.create(goldenInterface(groupId, 0)); // maxRetries=0
        interfaceService.publish(ifaceId);

        // 直接构造一条到期的 COMPENSATING 记录（模拟首送已耗尽进入补偿队列）
        jdbcTemplate.update("""
                INSERT INTO outbound_request (interface_id, app_id, biz_id, in_payload, status,
                                              attempt_count, max_attempts, next_retry_at, trace_id)
                VALUES (?, ?, ?, ?, 'COMPENSATING', 1, 5, NOW() - INTERVAL 1 SECOND, 'trace-replay')
                """, ifaceId, TEST_APP, "biz-replay-timeout", GOLDEN_REQUEST);

        compensationWorker.scan();

        OutboundRequestRow row = outboundRequestRepository.findByBizId(TEST_APP, "biz-replay-timeout").get(0);
        assertThat(row.status()).isEqualTo("UNKNOWN");
        assertThat(row.errorCode()).isEqualTo("50401");
    }

    // ---------- helpers ----------

    private InterfaceRequest goldenInterface(long groupId, int maxRetries) {
        return goldenInterface(groupId, maxRetries, false);
    }

    /** inheritAuth=true：AUTH 绑定行存在但 adapter_id 为空（管理面保存形态，继承应用默认） */
    private InterfaceRequest goldenInterface(long groupId, int maxRetries, boolean inheritAuth) {
        boolean noRetry = maxRetries == 0;
        List<ParamDto> inParams = List.of(
                new ParamDto("IN", "filter.seller_id", "string", true, "7494312521977267257", 1),
                new ParamDto("IN", "orderby", "array", false, null, 2),
                new ParamDto("IN", "page", "number", false, "1", 3),
                new ParamDto("IN", "pagesize", "number", false, "1", 4));
        List<ParamDto> outParams = inParams.stream()
                .map(p -> new ParamDto("OUT", p.name(), p.type(), p.required(), p.sample(), p.sortOrder()))
                .toList();
        List<ParamDto> all = new java.util.ArrayList<>(inParams);
        all.addAll(outParams);
        return new InterfaceRequest(
                noRetry ? "M2-GOLDEN-NR" : "M2-GOLDEN-1", "M2 黄金用例", "OUTBOUND", "POST",
                noRetry ? "/test/m2/golden-no-retry" : "/test/m2/golden",
                "JSON", "JSON", TEST_APP, groupId,
                "/shop/v1/creatorList", null, null, 3000, maxRetries, "WireMock 黄金用例", 1,
                all, List.of(), List.of(),
                List.of(
                        new FieldDefDto("RESP", "total", "number", "结果总数", 1),
                        new FieldDefDto("RESP", "list", "array", "达人列表", 2)),
                List.of(
                        new BindingDto("AUTH", inheritAuth ? null : "ADP-101", null),
                        new BindingDto("MESSAGE", "ADP-201", null)));
    }

    private void insertAdapterIfAbsent(String id, String name, String type, String impl, String params) {
        if (!adapterRepository.existsById(id)) {
            adapterRepository.insert(new AdapterRow(id, name, type, impl, true, "1.0", params, null, null));
        }
    }
}
