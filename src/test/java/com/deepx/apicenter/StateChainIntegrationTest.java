package com.deepx.apicenter;

import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.engine.OutboundEngine;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.OutboundRequestStateLogRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.service.AppService;
import com.deepx.apicenter.service.GroupService;
import com.deepx.apicenter.service.InterfaceService;
import com.deepx.apicenter.service.MonitorService;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态链集成测试（M5 后状态链，设计 §4.6）：验证 outbound_request_state_log 事件溯源——
 * 成功 / 4xx 死信 / 5xx 补偿（detail 带短重试次数）/ 超时 UNKNOWN / 人工对账（RECONCILE_MANUAL）/
 * 死信重放（REPLAY）/ 补偿重放（COMPENSATE）各分支的状态链节点序列与 trigger。
 * 经真实链路：engine.dispatch → 链执行 → UpstreamInvoker（WireMock 上游）。worker 拉长（用例手动驱动）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.api-center.retry-worker-fixed-delay-ms=3600000",
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        // 首跑延迟置大：用例手动驱动 scan()，避免「启动首跑」与造数/断言竞态（2026-09-12）
        "app.api-center.retry-worker-initial-delay-ms=3600000",
        "app.api-center.alert-worker-initial-delay-ms=3600000"
})
class StateChainIntegrationTest {

    private static final String TEST_APP = "SCT-APP";
    private static final int WM_PORT = 18080;
    private static final String WM_BASE = "http://localhost:" + WM_PORT;

    private static WireMockServer wireMock;

    @LocalServerPort
    private int port;

    @Autowired private AppService appService;
    @Autowired private GroupService groupService;
    @Autowired private InterfaceService interfaceService;
    @Autowired private AppRepository appRepository;
    @Autowired private InterfaceRepository interfaceRepository;
    @Autowired private OutboundRequestRepository outboundRequestRepository;
    @Autowired private MonitorService monitorService;
    @Autowired private OutboundEngine outboundEngine;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long groupId;

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
        cleanupApp();
        appService.create(new AppRequest(TEST_APP, "状态链测试供应商", null,
                null, null, null, WM_BASE, null, null, null, null,
                "状态链集成测试（Noop 鉴权）"));
        appService.enable(TEST_APP);
        groupId = groupService.create(new GroupRequest(TEST_APP, "测试分组", 0));
    }

    @AfterEach
    void cleanup() {
        wireMock.resetAll();
        cleanupApp();
    }

    private void cleanupApp() {
        if (appRepository.existsById(TEST_APP)) {
            outboundRequestRepository.deleteByApp(TEST_APP);
            jdbcTemplate.queryForList("SELECT id FROM interface WHERE app_id = ?", Long.class, TEST_APP)
                    .forEach(interfaceRepository::deleteCascade);
            appRepository.deleteCascade(TEST_APP);
        }
    }

    // ---------- 用例 ----------

    @Test
    void 首送成功_状态链INIT_MAPPING_SUCCESS() {
        long ifaceId = createIface("IF-SCT-OK", "/sct/ok", "/up-ok", 4);
        stubFor(post("/up-ok").willReturn(okJson("{\"data\":{\"v\":1},\"code\":\"0\"}")));

        assertThat(outboundEngine.dispatch("/sct/ok", "POST",
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "biz-ok", "trace-ok").code()).isZero();

        List<OutboundRequestStateLogRow> chain = chain("biz-ok");
        assertThat(chain).extracting(OutboundRequestStateLogRow::toStatus)
                .containsExactly("INIT", "MAPPING", "SUCCESS");
        assertThat(chain).allSatisfy(s -> assertThat(s.trigger()).isEqualTo("FIRST_SEND"));
        assertThat(chain.get(0).fromStatus()).isNull(); // 首条 = 创建为 INIT
        assertThat(chain.get(chain.size() - 1).detail()).contains("响应"); // SUCCESS detail 带响应字节
    }

    @Test
    void 首送4xx_状态链转DEAD_LETTER() {
        long ifaceId = createIface("IF-SCT-DEAD", "/sct/dead", "/up-dead", 4);
        stubFor(post("/up-dead").willReturn(aResponse().withStatus(401).withBody("denied")));

        try {
            outboundEngine.dispatch("/sct/dead", "POST",
                    "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "biz-dead", "trace-dead");
        } catch (BizException e) {
            assertThat(e.getCode()).isEqualTo(50201);
        }

        List<OutboundRequestStateLogRow> chain = chain("biz-dead");
        assertThat(chain).extracting(OutboundRequestStateLogRow::toStatus)
                .containsExactly("INIT", "MAPPING", "DEAD_LETTER");
        assertThat(chain.get(2).errorCode()).isEqualTo("50201");
        assertThat(chain.get(2).detail()).contains("4xx");
    }

    @Test
    void 首送5xx_短重试耗尽_COMPENSATING_detail带短重试次数() {
        createIface("IF-SCT-5XX", "/sct/5xx", "/up-5xx", 4);
        stubFor(post("/up-5xx").willReturn(aResponse().withStatus(500).withBody("boom")));

        try {
            outboundEngine.dispatch("/sct/5xx", "POST",
                    "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "biz-5xx", "trace-5xx");
        } catch (BizException e) {
            assertThat(e.getCode()).isEqualTo(50201);
        }

        List<OutboundRequestStateLogRow> chain = chain("biz-5xx");
        assertThat(chain).extracting(OutboundRequestStateLogRow::toStatus)
                .containsExactly("INIT", "MAPPING", "COMPENSATING");
        assertThat(chain.get(2).trigger()).isEqualTo("FIRST_SEND");
        assertThat(chain.get(2).errorCode()).isEqualTo("50201");
        // 短重试次数 = maxRetries（4 次预算）：设计坑 1 修复后 detail 必须带真实次数（非 0）
        assertThat(chain.get(2).detail()).contains("短重试 4 次");
    }

    @Test
    void 首送超时_UNKNOWN_人工对账_SUCCESS_状态链含RECONCILE_MANUAL() {
        createIface("IF-SCT-UNK", "/sct/unk", "/up-unk", 0); // maxRetries=0：首送即超时
        stubFor(post("/up-unk").willReturn(okJson("{}").withFixedDelay(4000))); // 4s > 接口读超时 3s

        try {
            outboundEngine.dispatch("/sct/unk", "POST",
                    "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "biz-unk", "trace-unk");
        } catch (BizException e) {
            assertThat(e.getCode()).isEqualTo(50401);
        }

        List<OutboundRequestStateLogRow> chain = chain("biz-unk");
        assertThat(chain).extracting(OutboundRequestStateLogRow::toStatus)
                .containsExactly("INIT", "MAPPING", "UNKNOWN");
        assertThat(chain.get(2).errorCode()).isEqualTo("50401");

        // 人工对账置为已到达 → 追加 RECONCILE_MANUAL 节点
        long id = idOf("biz-unk");
        monitorService.reconcile(id, "SUCCESS", "admin", "供应商确认已到达");

        List<OutboundRequestStateLogRow> after = chain("biz-unk");
        assertThat(after).hasSize(4);
        assertThat(after.get(3).fromStatus()).isEqualTo("UNKNOWN");
        assertThat(after.get(3).toStatus()).isEqualTo("SUCCESS");
        assertThat(after.get(3).trigger()).isEqualTo("RECONCILE_MANUAL");
        assertThat(after.get(3).detail()).contains("admin");
        // 监控详情接口已并入 stateChain（前端一次拉取）
        assertThat(monitorService.outboundDetail(id).stateChain()).hasSize(4);
    }

    @Test
    void 死信重放_状态链追加REPLAY节点() {
        createIface("IF-SCT-REPLAY", "/sct/replay", "/up-replay", 4);
        stubFor(post("/up-replay").willReturn(aResponse().withStatus(400).withBody("bad")));

        try {
            outboundEngine.dispatch("/sct/replay", "POST",
                    "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "biz-replay", "trace-replay");
        } catch (BizException e) {
            assertThat(e.getCode()).isEqualTo(50201);
        }
        long id = idOf("biz-replay");
        List<OutboundRequestStateLogRow> before = chain("biz-replay");
        assertThat(before).extracting(OutboundRequestStateLogRow::toStatus)
                .containsExactly("INIT", "MAPPING", "DEAD_LETTER");

        // 死信 PENDING → 重放（置回 COMPENSATING）
        Long deadId = jdbcTemplate.queryForObject(
                "SELECT id FROM dead_letter WHERE biz_type = 'OUTBOUND' AND ref_id = ?", Long.class, id);
        monitorService.replayDeadLetter(deadId);

        List<OutboundRequestStateLogRow> after = chain("biz-replay");
        assertThat(after).hasSize(4);
        assertThat(after.get(3).fromStatus()).isEqualTo("DEAD_LETTER");
        assertThat(after.get(3).toStatus()).isEqualTo("COMPENSATING");
        assertThat(after.get(3).trigger()).isEqualTo("REPLAY");
        assertThat(after.get(3).attempt()).isZero(); // 重放后新预算起点
    }

    @Test
    void 补偿重放_成功_状态链含COMPENSATE链路() {
        createIface("IF-SCT-COMP", "/sct/comp", "/up-comp", 0);
        // 先 5xx（首送 → COMPENSATING），随后上游恢复 200
        stubFor(post("/up-comp").willReturn(aResponse().withStatus(500).withBody("boom")));
        try {
            outboundEngine.dispatch("/sct/comp", "POST",
                    "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "biz-comp", "trace-comp");
        } catch (BizException ignored) {
        }
        assertThat(chain("biz-comp")).extracting(OutboundRequestStateLogRow::toStatus)
                .containsExactly("INIT", "MAPPING", "COMPENSATING");

        // 上游恢复 → 手动驱动补偿重放（走 doInvoke(compensate=true)；attempt 前置 +1）
        wireMock.resetAll();
        stubFor(post("/up-comp").willReturn(okJson("{\"data\":{\"v\":1},\"code\":\"0\"}")));
        long id = idOf("biz-comp");
        jdbcTemplate.update("UPDATE outbound_request SET next_retry_at = NOW() WHERE id = ?", id);
        com.deepx.apicenter.model.OutboundRequestRow row = jdbcTemplate.queryForObject(
                "SELECT * FROM outbound_request WHERE id = ?",
                com.deepx.apicenter.model.OutboundRequestRow.MAPPER, id);
        outboundEngine.replay(row);

        List<OutboundRequestStateLogRow> after = chain("biz-comp");
        assertThat(after).extracting(OutboundRequestStateLogRow::toStatus)
                .containsExactly("INIT", "MAPPING", "COMPENSATING", "MAPPING", "SUCCESS");
        // 补偿重放段（第 4、5 节点）trigger = COMPENSATE，且重放段 MAPPING 由 COMPENSATING 转来
        assertThat(after.get(3).fromStatus()).isEqualTo("COMPENSATING");
        assertThat(after.get(3).trigger()).isEqualTo("COMPENSATE");
        assertThat(after.get(4).trigger()).isEqualTo("COMPENSATE");
    }

    // ---------- helpers ----------

    private long idOf(String bizId) {
        return outboundRequestRepository.findByBizId(TEST_APP, bizId).get(0).id();
    }

    private List<OutboundRequestStateLogRow> chain(String bizId) {
        return outboundRequestRepository.stateChain(idOf(bizId));
    }

    private long createIface(String code, String path, String upstream, int maxRetries) {
        long id = interfaceService.create(new InterfaceRequest(
                code, code, "OUTBOUND", "POST", path,
                "JSON", "JSON", TEST_APP, groupId, upstream, null, null,
                3000, maxRetries, "状态链测试接口", 1,
                List.of(), List.of(), List.of(), List.of(), List.of()));
        interfaceService.publish(id);
        return id;
    }
}
