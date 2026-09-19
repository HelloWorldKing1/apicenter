package com.deepx.apicenter;

import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.MappingDto;
import com.deepx.apicenter.dto.InterfaceDtos.StepDto;
import com.deepx.apicenter.engine.CircuitBreakerRegistry;
import com.deepx.apicenter.engine.OutboundEngine;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.model.OutboundRequestStateLogRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.service.AppService;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 前置接口编排集成测试（《前置接口编排设计方案》v0.1.3 PS-8）。
 *
 * <p>覆盖：串行两步 + 命名空间合入 → 宿主映射引用 → 第三方报文；模型隔离（前置看不到宿主步骤输出）；
 * 透传防护（宿主无映射时 steps 不外泄）；失败传播四分支（4xx 链失败 / 5xx 顺延 / 超时 UNKNOWN / 熔断短路）；
 * 步骤留痕；配置校验（环 / 超深 / 未发布 / 保留名）；被引用删除守卫；快照含 steps 与回滚恢复；
 * D-PS-11 补偿预算下限；运行期深度防护。
 *
 * <p>worker 全部拉长（用例手动驱动 scan），与其他集成测试同纪律。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.api-center.retry-worker-fixed-delay-ms=3600000",
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-initial-delay-ms=3600000",
        "app.api-center.alert-worker-initial-delay-ms=3600000",
        // 前置响应体上限调小（默认 262144）：让「超限拒接」用例用一个小报文就能验证
        "app.api-center.pre-step.max-response-bytes=64"
})
class PreStepIntegrationTest {

    private static final String TEST_APP = "PST-APP";
    private static final int WM_PORT = 18080;
    private static final String WM_BASE = "http://localhost:" + WM_PORT;
    private static final String IN_BODY = "{\"orderId\":\"O-1\"}";

    private static WireMockServer wireMock;

    @Autowired private AppService appService;
    @Autowired private GroupService groupService;
    @Autowired private InterfaceService interfaceService;
    @Autowired private AppRepository appRepository;
    @Autowired private InterfaceRepository interfaceRepository;
    @Autowired private OutboundRequestRepository outboundRequestRepository;
    @Autowired private OutboundEngine outboundEngine;
    @Autowired private CompensationWorker compensationWorker;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
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
        circuitBreakerRegistry.resetAll();
        cleanupApp();
        appService.create(new AppRequest(TEST_APP, "前置编排测试供应商", null,
                null, null, null, WM_BASE, null, null, null, null, "前置编排集成测试（Noop 鉴权 / Noop 报文）"));
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

    // ---------- 1. 串行两步 + 命名空间 + 映射引用 + 模型隔离 ----------

    @Test
    void 串行两步_结果合入steps_宿主映射写入第三方报文_前置看不到步骤输出() {
        long auth = createIface("IF-PS-AUTH", "/ps/auth", "/up-auth", 0, 3000, List.of());
        long route = createIface("IF-PS-ROUTE", "/ps/route", "/up-route", 0, 3000, List.of());
        long main = createIface("IF-PS-MAIN", "/ps/main", "/up-main", 0, 3000, List.of(
                new StepDto(0, "auth", auth, "ABORT", true),
                new StepDto(1, "route", route, "ABORT", true)),
                List.of(new MappingDto("steps.auth.token", "rename", "api_token", null, "KEEP", 0),
                        new MappingDto("steps.route.region", "rename", "region_code", null, "KEEP", 1)));

        stubFor(post("/up-auth").willReturn(okJson("{\"token\":\"T-123\",\"code\":0}")));
        stubFor(post("/up-route").willReturn(okJson("{\"region\":\"US\"}")));
        stubFor(post("/up-main").willReturn(okJson("{\"ok\":true}")));

        assertThat(outboundEngine.dispatch("/ps/main", "POST",
                IN_BODY.getBytes(StandardCharsets.UTF_8), "biz-ps-1", "trace-ps-1").code()).isZero();

        // ① 两跳都真调了，且按 seq 串行（第一步结果先合入）
        wireMock.verify(1, postRequestedFor(urlEqualTo("/up-auth")));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/up-route")));
        // ② 宿主映射引用了 steps.* → 第三方报文含映射后的值
        wireMock.verify(postRequestedFor(urlEqualTo("/up-main"))
                .withRequestBody(containing("T-123"))
                .withRequestBody(containing("region_code"))
                .withRequestBody(notContaining("steps")));   // 保留键不外泄
        // ③ 模型隔离：前置收到的入参 = 宿主 IN 报文（不含步骤输出）
        wireMock.verify(postRequestedFor(urlEqualTo("/up-auth"))
                .withRequestBody(equalToJson(IN_BODY, true, true)));
        // ④ 步骤留痕：两条 PRE_STEP 节点（INIT→INIT 同态，批量通道）
        OutboundRequestRow row = outboundRequestRepository.findByBizId(TEST_APP, "biz-ps-1").get(0);
        List<OutboundRequestStateLogRow> chain = outboundRequestRepository.stateChain(row.id());
        assertThat(chain).filteredOn(n -> "PRE_STEP".equals(n.trigger()))
                .extracting(OutboundRequestStateLogRow::detail)
                .hasSize(2)
                .allSatisfy(d -> assertThat(d).contains("HTTP 200"));
        assertThat(chain.get(chain.size() - 1).toStatus()).isEqualTo("SUCCESS");

        // ⑤ 调用日志「按步骤筛选」脏数据源（PS-6 可观测二期）：B 的 OUT 条带 step_code=auth（异步批量写，轮询）
        long deadline = System.currentTimeMillis() + 8000;
        int logRows = 0;
        while (System.currentTimeMillis() < deadline) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM call_log WHERE step_code = 'auth' AND direction = 'OUT'", Integer.class);
            logRows = n == null ? 0 : n;
            if (logRows > 0) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(logRows).as("前置调用的 OUT 条应带 step_code").isGreaterThan(0);
    }

    /**
     * 回归（2026-09-18 评审修复）：**第一个前置步骤时，B 的 MAPPING 不得污染宿主模型**。
     *
     * <p>背景：宿主的链路载体是 `AdapterContext.payload`；`withoutSteps` 早期在「无 steps 键」时
     * 原样返回**同一实例**（首个步骤必然如此），而 `MappingEngine` 规则非空会执行
     * `ctx.payload().root(newRoot)` → 把 B 的映射结果写回宿主载体，宿主随后的字段映射会基于
     * **B 的映射输出**而不是宿主自己的入站报文。本用例让 B 带映射（rename）后断言宿主映射仍读自己的入站字段。
     */
    @Test
    void 前置接口自身带映射_不得污染宿主模型() {
        // B 带映射：token → tk（非空规则 → 会替换 B 的 payload root）
        long auth = createIface("IF-PS-BMAP", "/ps/bmap", "/up-bmap", 0, 3000, List.of(),
                List.of(new MappingDto("token", "rename", "tk", null, "KEEP", 0)));
        // 宿主：一个步骤 + 两组映射（一组引用步骤输出、一组引用宿主自己的入站字段）
        // 注：B 的响应体是 `{"token":"T-BMAP"}`（B 的 request 映射 token→tk 只作用于 B 的出站报文，不影响 B 的响应）
        createIface("IF-PS-BMAP-MAIN", "/ps/bmap-main", "/up-bmap-main", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)),
                List.of(new MappingDto("steps.auth.token", "rename", "api_token", null, "KEEP", 0),
                        new MappingDto("orderId", "rename", "order_id", null, "KEEP", 1)));

        stubFor(post("/up-bmap").willReturn(okJson("{\"token\":\"T-BMAP\"}")));
        stubFor(post("/up-bmap-main").willReturn(okJson("{\"ok\":true}")));

        assertThat(outboundEngine.dispatch("/ps/bmap-main", "POST",
                IN_BODY.getBytes(StandardCharsets.UTF_8), "biz-ps-bmap", "trace-ps-bmap").code()).isZero();

        // B 自己的出站报文应为其映射产物（token 源不存在 → tk=null，而非原入站报文的 orderId）
        wireMock.verify(postRequestedFor(urlEqualTo("/up-bmap")).withRequestBody(containing("tk")));
        // B 的响应交给宿主（steps.auth.token）→ api_token 拿到值；
        // 且宿主自己映射的 orderId → order_id 必须来自**宿主的入站报文**；报文里不得出现保留键 steps。
        // 用精确 JSON 相等断言（键序无关）：修复前该报文是 {"api_token":null,"order_id":null}（宿主载体被 B 的映射结果覆盖）
        wireMock.verify(postRequestedFor(urlEqualTo("/up-bmap-main"))
                .withRequestBody(equalToJson("{\"api_token\":\"T-BMAP\",\"order_id\":\"O-1\"}"))
                .withRequestBody(notContaining("steps")));
    }

    /**
     * 回归（2026-09-18 真实事故）：**被调接口自己的映射负责把「宿主入站模型」适配成它自己的报文**。
     *
     * <p>背景：前置调用的入参 = 宿主当前模型**原样**（`ReservedKeys.withoutSteps`，DECODE 跳过），
     * 被调接口的 IN 参数声明**不参与取值**；若被调接口映射为空 → 整体透传 → 宿主的多余字段与扁平字段名
     * 会被原样发给第三方（真实场景：FastMoss 收到 `{"seller_id":…,"prompt":…}` 返回 `code=1 params error`）。
     *
     * <p>本用例断言三件事：① 映射能构造**嵌套**报文字段（`seller_id` → `filter.seller_id`）；
     * ② 非空规则 = **白名单**，宿主多余字段（`prompt`）不得泄漏；
     * ③ `nullStrategy=NULL` 的缺席字段**省略**（写成 JSON null 也会被第三方判为参数错误）。
     */
    @Test
    void 前置接口映射把宿主扁平报文适配成第三方嵌套报文_白名单且缺席字段省略() {
        long adapt = createIface("IF-PS-ADAPT", "/ps/adapt", "/up-adapt", 0, 3000, List.of(),
                List.of(new MappingDto("seller_id", "rename", "filter.seller_id", null, "KEEP", 0),
                        new MappingDto("page", "rename", "page", null, "NULL", 1)));
        createIface("IF-PS-ADAPT-MAIN", "/ps/adapt-main", "/up-adapt-main", 0, 3000,
                List.of(new StepDto(0, "fm", adapt, "ABORT", true)));   // 宿主无映射 → 宿主侧仍整体透传

        stubFor(post("/up-adapt").willReturn(okJson("{\"code\":0,\"data\":{\"total\":3}}")));
        stubFor(post("/up-adapt-main").willReturn(okJson("{\"ok\":true}")));

        assertThat(outboundEngine.dispatch("/ps/adapt-main", "POST",
                "{\"seller_id\":\"S-1\",\"prompt\":\"达人简报\"}".getBytes(StandardCharsets.UTF_8),
                "biz-ps-adapt", "trace-ps-adapt").code()).isZero();

        wireMock.verify(postRequestedFor(urlEqualTo("/up-adapt"))
                .withRequestBody(equalToJson("{\"filter\":{\"seller_id\":\"S-1\"}}"))   // 严格相等
                .withRequestBody(notContaining("prompt"))       // 白名单：宿主字段不外泄
                .withRequestBody(notContaining("null")));        // NULL 策略 = 省略，而非写 null
    }

    // ---------- 2. 透传防护（宿主无映射 = 整体透传） ----------

    @Test
    void 透传模式_步骤输出不外泄给第三方() {
        long auth = createIface("IF-PS-AUTH2", "/ps/auth2", "/up-auth2", 0, 3000, List.of());
        createIface("IF-PS-PASS", "/ps/pass", "/up-pass", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)));   // 无映射规则 → 透传

        stubFor(post("/up-auth2").willReturn(okJson("{\"token\":\"SECRET-TOKEN\"}")));
        stubFor(post("/up-pass").willReturn(okJson("{\"ok\":true}")));

        assertThat(outboundEngine.dispatch("/ps/pass", "POST",
                IN_BODY.getBytes(StandardCharsets.UTF_8), "biz-ps-2", "trace-ps-2").code()).isZero();

        // 宿主出站报文 = IN 报文原样（steps 已被 ENCODE 前剥离）
        wireMock.verify(postRequestedFor(urlEqualTo("/up-pass"))
                .withRequestBody(equalToJson(IN_BODY, true, true))
                .withRequestBody(notContaining("steps"))
                .withRequestBody(notContaining("SECRET-TOKEN")));
    }

    // ---------- 3. 失败传播四分支 ----------

    @Test
    void 前置4xx_宿主链失败_记录停留INIT() {
        long auth = createIface("IF-PS-AUTH3", "/ps/auth3", "/up-auth3", 0, 3000, List.of());
        createIface("IF-PS-MAIN3", "/ps/main3", "/up-main3", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)));
        stubFor(post("/up-auth3").willReturn(aResponse().withStatus(404).withBody("denied")));

        assertThatThrownBy(() -> outboundEngine.dispatch("/ps/main3", "POST",
                IN_BODY.getBytes(StandardCharsets.UTF_8), "biz-ps-3", "trace-ps-3"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("前置步骤 auth 失败")
                .hasMessageContaining("404");

        OutboundRequestRow row = outboundRequestRepository.findByBizId(TEST_APP, "biz-ps-3").get(0);
        assertThat(row.status()).isEqualTo("INIT");   // 不推进状态机（无死信、不入补偿）
        assertThat(row.errorCode()).isNull();
        // 留痕节点带失败码
        assertThat(outboundRequestRepository.stateChain(row.id()))
                .filteredOn(n -> "PRE_STEP".equals(n.trigger()))
                .singleElement()
                .satisfies(n -> assertThat(n.errorCode()).isEqualTo("40001"));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/up-main3")));   // 不触达第三方
    }

    @Test
    void 前置5xx耗尽_宿主转COMPENSATING_补偿预算下限生效_上游恢复后能重放成功() {
        long auth = createIface("IF-PS-AUTH4", "/ps/auth4", "/up-auth4", 0, 3000, List.of());
        // 宿主 max_retries=0：D-PS-11 生效后 max_attempts 应为 2（否则首次扫描即判耗尽）
        long main = createIface("IF-PS-MAIN4", "/ps/main4", "/up-main4", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)));
        stubFor(post("/up-auth4").willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> outboundEngine.dispatch("/ps/main4", "POST",
                IN_BODY.getBytes(StandardCharsets.UTF_8), "biz-ps-4", "trace-ps-4"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("补偿队列");

        OutboundRequestRow row = outboundRequestRepository.findByBizId(TEST_APP, "biz-ps-4").get(0);
        assertThat(row.status()).isEqualTo("COMPENSATING");
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.maxAttempts()).as("D-PS-11：前置宿主强制补偿预算 ≥1").isEqualTo(2);

        // 上游恢复 → 手动驱动补偿 → 重放成功（证明该预算真的换来一次补偿机会）
        wireMock.resetAll();
        stubFor(post("/up-auth4").willReturn(okJson("{\"token\":\"T-4\"}")));
        stubFor(post("/up-main4").willReturn(okJson("{\"ok\":true}")));
        // 置为**过去**时间而非 NOW()：远程库与应用的时钟偏差会让 NOW() 落入未来 → 扫描不命中（用例抖）
        jdbcTemplate.update("UPDATE outbound_request SET next_retry_at = NOW() - INTERVAL 5 SECOND WHERE id = ?",
                row.id());
        compensationWorker.scan();

        assertThat(outboundRequestRepository.findById(row.id()).orElseThrow().status()).isEqualTo("SUCCESS");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/up-main4")));
    }

    @Test
    void 前置读超时_宿主转UNKNOWN() {
        long auth = createIface("IF-PS-AUTH5", "/ps/auth5", "/up-auth5", 0, 300, List.of());  // 300ms 读超时
        createIface("IF-PS-MAIN5", "/ps/main5", "/up-main5", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)));
        stubFor(post("/up-auth5").willReturn(okJson("{\"token\":\"x\"}").withFixedDelay(1500)));

        assertThatThrownBy(() -> outboundEngine.dispatch("/ps/main5", "POST",
                IN_BODY.getBytes(StandardCharsets.UTF_8), "biz-ps-5", "trace-ps-5"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("UNKNOWN");

        OutboundRequestRow row = outboundRequestRepository.findByBizId(TEST_APP, "biz-ps-5").get(0);
        assertThat(row.status()).isEqualTo("UNKNOWN");
        assertThat(row.errorCode()).isEqualTo("50401");
    }

    @Test
    void 前置熔断OPEN_宿主顺延且不触达上游() {
        long auth = createIface("IF-PS-AUTH6", "/ps/auth6", "/up-auth6", 0, 3000, List.of());
        createIface("IF-PS-MAIN6", "/ps/main6", "/up-main6", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)));
        stubFor(post("/up-auth6").willReturn(okJson("{\"token\":\"x\"}")));

        // 直接把前置接口的熔断器打到 OPEN（默认最小 10 次调用 / 失败率 50%）
        for (int i = 0; i < 10; i++) {
            circuitBreakerRegistry.record(auth, false);
        }
        assertThat(circuitBreakerRegistry.stateOf(auth)).isEqualTo(
                com.deepx.apicenter.engine.CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> outboundEngine.dispatch("/ps/main6", "POST",
                IN_BODY.getBytes(StandardCharsets.UTF_8), "biz-ps-6", "trace-ps-6"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(50202));

        OutboundRequestRow row = outboundRequestRepository.findByBizId(TEST_APP, "biz-ps-6").get(0);
        assertThat(row.status()).isEqualTo("COMPENSATING");
        assertThat(row.errorCode()).isEqualTo("50202");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/up-auth6")));   // 短路未触达上游
        wireMock.verify(0, postRequestedFor(urlEqualTo("/up-main6")));
    }

    // ---------- 4. 配置校验（保存期权威） ----------

    @Test
    void 配置校验_环_超深_未发布_保留名_自引用() {
        long auth = createIface("IF-PS-V-AUTH", "/ps/v-auth", "/up-v-auth", 0, 3000, List.of());
        long draft = createDraftIface("IF-PS-V-DRAFT", "/ps/v-draft", "/up-v-draft");
        // main 先指向 auth（这样再把 auth 指向 main 才构成环 A→B→A）
        long main = createIface("IF-PS-V-MAIN", "/ps/v-main", "/up-v-main", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)));

        // ① 目标未发布（D-PS-8）
        assertThatThrownBy(() -> createIface("IF-PS-V-1", "/ps/v-1", "/up-v-1", 0, 3000,
                List.of(new StepDto(0, "auth", draft, "ABORT", true))))
                .isInstanceOf(BizException.class).hasMessageContaining("未发布");
        // ② 自引用
        assertThatThrownBy(() -> updateIface(main, "/ps/v-main", "/up-v-main",
                List.of(new StepDto(0, "self", main, "ABORT", true))))
                .isInstanceOf(BizException.class).hasMessageContaining("不能指向接口自身");
        // ③ 立即成环（A→B→A）
        assertThatThrownBy(() -> updateIface(auth, "/ps/v-auth", "/up-v-auth",
                List.of(new StepDto(0, "back", main, "ABORT", true))))
                .isInstanceOf(BizException.class).hasMessageContaining("存在环");
        // ④ 步骤名非法 / 重复 / 保留名
        assertThatThrownBy(() -> updateIface(main, "/ps/v-main", "/up-v-main",
                List.of(new StepDto(0, "bad.name", auth, "ABORT", true))))
                .isInstanceOf(BizException.class).hasMessageContaining("仅允许字母/数字/下划线");
        assertThatThrownBy(() -> updateIface(main, "/ps/v-main", "/up-v-main",
                List.of(new StepDto(0, "dup", auth, "ABORT", true), new StepDto(1, "dup", auth, "ABORT", true))))
                .isInstanceOf(BizException.class).hasMessageContaining("步骤名重复");
        assertThatThrownBy(() -> updateIface(main, "/ps/v-main", "/up-v-main",
                List.of(new StepDto(0, "steps", auth, "ABORT", true))))
                .isInstanceOf(BizException.class).hasMessageContaining("不得为保留名 steps");
        // ⑤ 失败策略二期能力
        assertThatThrownBy(() -> updateIface(main, "/ps/v-main", "/up-v-main",
                List.of(new StepDto(0, "auth", auth, "CONTINUE", true))))
                .isInstanceOf(BizException.class).hasMessageContaining("仅支持 ABORT");
    }

    @Test
    void 超深链_A到B到C到D_保存期拒绝() {
        long d = createIface("IF-PS-L-D", "/ps/l-d", "/up-l-d", 0, 3000, List.of());
        long c = createIface("IF-PS-L-C", "/ps/l-c", "/up-l-c", 0, 3000,
                List.of(new StepDto(0, "d", d, "ABORT", true)));                      // c→d = 2 节点
        long b = createIface("IF-PS-L-B", "/ps/l-b", "/up-l-b", 0, 3000,
                List.of(new StepDto(0, "c", c, "ABORT", true)));                      // b→c→d = 3 节点（上限内）
        // A1→b→c→d = 4 节点 > 3 → 拒绝
        assertThatThrownBy(() -> createIface("IF-PS-L-A1", "/ps/l-a1", "/up-l-a1", 0, 3000,
                List.of(new StepDto(0, "b", b, "ABORT", true))))
                .isInstanceOf(BizException.class).hasMessageContaining("前置链长度超限");
        // A2→c→d = 3 节点 = 上限 → 放行（边界）
        long a2 = createIface("IF-PS-L-A2", "/ps/l-a2", "/up-l-a2", 0, 3000,
                List.of(new StepDto(0, "c", c, "ABORT", true)));
        assertThat(interfaceRepository.findSteps(a2)).hasSize(1);
    }

    // ---------- 5. 引用守卫 / 快照 / 运行期深度 ----------

    @Test
    void 被引用为前置的接口_禁止删除() {
        long auth = createIface("IF-PS-DEL-AUTH", "/ps/del-auth", "/up-del-auth", 0, 3000, List.of());
        createIface("IF-PS-DEL-MAIN", "/ps/del-main", "/up-del-main", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)));

        assertThatThrownBy(() -> interfaceService.delete(auth))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("被 1 个前置步骤引用")
                .hasMessageContaining("IF-PS-DEL-MAIN(步骤 auth)");
    }

    @Test
    void 快照含steps_回滚恢复步骤() {
        long auth = createIface("IF-PS-SNAP-AUTH", "/ps/snap-auth", "/up-snap-auth", 0, 3000, List.of());
        long main = createIface("IF-PS-SNAP-MAIN", "/ps/snap-main", "/up-snap-main", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)));

        // 移除步骤 → 新版本快照记录「前置步骤 1→0」
        updateIface(main, "/ps/snap-main", "/up-snap-main", List.of());
        InterfaceRow afterRemove = interfaceRepository.findById(main).orElseThrow();
        assertThat(interfaceRepository.findSteps(main)).isEmpty();
        String v2 = snapshotConfig(main, afterRemove.version());
        assertThat(v2).contains("\"steps\":[]");

        // 回滚到 v1.0（含步骤）→ 步骤恢复
        interfaceService.rollback(main, new com.deepx.apicenter.dto.InterfaceDtos.RollbackRequest(
                BigDecimal.valueOf(1.0), null, null, afterRemove.version()));
        List<InterfaceRow.StepView> restored = interfaceRepository.findSteps(main);
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).stepCode()).isEqualTo("auth");
        assertThat(restored.get(0).targetInterfaceId()).isEqualTo(auth);
        assertThat(restored.get(0).targetCode()).isEqualTo("IF-PS-SNAP-AUTH");
    }

    @Test
    void 运行期深度防护_手工造环被拒() {
        long auth = createIface("IF-PS-REC-AUTH", "/ps/rec-auth", "/up-rec-auth", 0, 3000, List.of());
        long main = createIface("IF-PS-REC-MAIN", "/ps/rec-main", "/up-rec-main", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)));
        // 手工 SQL 把 B 也指回 A，绕过保存期校验（模拟改库/脏数据）
        jdbcTemplate.update("INSERT INTO interface_step (interface_id, seq, step_code, target_interface_id, "
                + "failure_policy, enabled) VALUES (?, 0, 'back', ?, 'ABORT', 1)", auth, main);

        assertThatThrownBy(() -> outboundEngine.dispatch("/ps/rec-main", "POST",
                IN_BODY.getBytes(StandardCharsets.UTF_8), "biz-ps-rec", "trace-ps-rec"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("深度超限");
        OutboundRequestRow recRow = outboundRequestRepository.findByBizId(TEST_APP, "biz-ps-rec").get(0);
        assertThat(recRow.status()).isEqualTo("INIT");
        // 2026-09-18 评审 P3#6：深度超限原先不留痕 → 现在补一条 PRE_STEP 节点（errorCode 40001）便于定位
        assertThat(outboundRequestRepository.stateChain(recRow.id()))
                .filteredOn(n -> "PRE_STEP".equals(n.trigger()))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.errorCode()).isEqualTo("40001");
                    assertThat(n.detail()).contains("深度超限");
                });
    }

    @Test
    void 被引用为前置的接口_下线允许但返回强提示() {
        long auth = createIface("IF-PS-OFF-AUTH", "/ps/off-auth", "/up-off-auth", 0, 3000, List.of());
        createIface("IF-PS-OFF-MAIN", "/ps/off-main", "/up-off-main", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)));

        // 下线本身不阻断（生命周期自由）
        interfaceService.offline(auth);
        assertThat(interfaceRepository.findById(auth).orElseThrow().status()).isEqualTo("OFFLINE");
        // 但返回 warnings[]（管理面强提示，D-PS-10）
        List<String> warnings = interfaceService.offlineWarnings(auth);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("IF-PS-OFF-MAIN");

        // 运行期：宿主硬失败（40001），而不是静默降级跳过前置
        assertThatThrownBy(() -> outboundEngine.dispatch("/ps/off-main", "POST",
                IN_BODY.getBytes(StandardCharsets.UTF_8), "biz-ps-off", "trace-ps-off"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未发布");
        assertThat(outboundRequestRepository.findByBizId(TEST_APP, "biz-ps-off").get(0).status())
                .isEqualTo("INIT");
    }

    @Test
    void 前置响应体超限_按链失败拒绝() {
        long auth = createIface("IF-PS-BIG-AUTH", "/ps/big-auth", "/up-big-auth", 0, 3000, List.of());
        createIface("IF-PS-BIG-MAIN", "/ps/big-main", "/up-big-main", 0, 3000,
                List.of(new StepDto(0, "auth", auth, "ABORT", true)));
        // 本测试类把上限调成 64 字节（见 @SpringBootTest properties）；此处返回 ~200 字节
        stubFor(post("/up-big-auth").willReturn(okJson(
                "{\"token\":\"" + "X".repeat(180) + "\"}")));

        assertThatThrownBy(() -> outboundEngine.dispatch("/ps/big-main", "POST",
                IN_BODY.getBytes(StandardCharsets.UTF_8), "biz-ps-big", "trace-ps-big"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过上限");
        assertThat(outboundRequestRepository.findByBizId(TEST_APP, "biz-ps-big").get(0).status())
                .as("超限按链失败处理（不截断后继续发错误报文）")
                .isEqualTo("INIT");
    }

    // ---------- helpers ----------

    private long createIface(String code, String path, String upstream, int maxRetries, int timeoutMs,
                             List<StepDto> steps) {
        return createIface(code, path, upstream, maxRetries, timeoutMs, steps, List.of());
    }

    private long createIface(String code, String path, String upstream, int maxRetries, int timeoutMs,
                             List<StepDto> steps, List<MappingDto> mappings) {
        long id = interfaceService.create(new InterfaceRequest(
                code, code, "OUTBOUND", "POST", path, "JSON", "JSON", TEST_APP, groupId,
                upstream, null, null, timeoutMs, maxRetries, "前置编排集成测试", BigDecimal.valueOf(1),
                List.of(), List.of(), mappings, List.of(),
                List.of(new BindingDto("MESSAGE", null, null)), steps));
        interfaceService.publish(id);
        return id;
    }

    private long createDraftIface(String code, String path, String upstream) {
        return interfaceService.create(new InterfaceRequest(
                code, code, "OUTBOUND", "POST", path, "JSON", "JSON", TEST_APP, groupId,
                upstream, null, null, 3000, 0, "未发布目标", BigDecimal.valueOf(1),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new BindingDto("MESSAGE", null, null)), List.of()));
    }

    /** 全量更新（带 steps）；version 由当前行读回，避免乐观锁冲突 */
    private void updateIface(long id, String path, String upstream, List<StepDto> steps) {
        InterfaceRow current = interfaceRepository.findById(id).orElseThrow();
        interfaceService.update(id, new InterfaceRequest(
                current.code(), current.name(), current.ifType(), current.method(), path,
                current.protocolIn(), current.protocolOut(), current.appId(), current.groupId(),
                upstream, null, null, current.timeoutMs(), current.maxRetries(), current.desc(),
                current.version(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BindingDto("MESSAGE", null, null)), steps), null);
    }

    private String snapshotConfig(long interfaceId, BigDecimal version) {
        return interfaceService.versionDetail(interfaceId, version).configJson();
    }
}
