package com.deepx.apicenter.engine;

import com.deepx.apicenter.adapter.protocol.JsonProtocolAdapter;
import com.deepx.apicenter.aspect.CallLogContext;
import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AppRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.service.AppService;

import static com.deepx.apicenter.repository.OutboundRequestRepository.TRIGGER_CIRCUIT_OPEN;
import static com.deepx.apicenter.repository.OutboundRequestRepository.TRIGGER_COMPENSATE;
import static com.deepx.apicenter.repository.OutboundRequestRepository.TRIGGER_FIRST_SEND;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 出站执行引擎（Flow A，设计 §6.1 状态机落地 + M0-03 异常映射表）：
 * 路由（平台侧路径 → PUBLISHED 接口）→ 应用启用校验 → 落 INIT → 链执行（映射）→ 调上游
 * → 结果分类：2xx+信封成败 → SUCCESS；4xx 非 429 → dead_letter + DEAD_LETTER；
 *   5xx/429 短重试耗尽 → COMPENSATING（补偿 worker 兜底）；读超时/连接异常 → UNKNOWN（对账）。
 * 链失败（解码/映射/编码/验签）直接错误响应，不落运行表（M0-01 D7）。
 */
@Service
public class OutboundEngine {

    private static final Logger log = LoggerFactory.getLogger(OutboundEngine.class);

    /** 熔断短路顺延上限（与补偿固定间隔 3s 口径对齐，D-M4-1） */
    private static final long CIRCUIT_DEFER_SECONDS = 3;

    private final InterfaceRepository interfaceRepository;
    private final AppRepository appRepository;
    private final OutboundRequestRepository outboundRequestRepository;
    private final ChainEngine chainEngine;
    private final UpstreamInvoker upstreamInvoker;
    private final AppService appService;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    /** 响应判定器（2026-09-18 从本类抽出，前置编排 PS-4）：宿主成功路径与前置步骤共用同一实现 */
    private final ResponseJudger responseJudger;

    public OutboundEngine(InterfaceRepository interfaceRepository,
                          AppRepository appRepository,
                          OutboundRequestRepository outboundRequestRepository,
                          ChainEngine chainEngine,
                          UpstreamInvoker upstreamInvoker,
                          AppService appService,
                          ObjectMapper objectMapper,
                          CircuitBreakerRegistry circuitBreakerRegistry,
                          ResponseJudger responseJudger) {
        this.interfaceRepository = interfaceRepository;
        this.appRepository = appRepository;
        this.outboundRequestRepository = outboundRequestRepository;
        this.chainEngine = chainEngine;
        this.upstreamInvoker = upstreamInvoker;
        this.appService = appService;
        this.objectMapper = objectMapper;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.responseJudger = responseJudger;
    }

    /**
     * 接入层入口：路由 + 执行。统一信封回调用方（设计 §6.2）。
     * 链失败抛 BizException（由全局异常处理转信封，不落运行表）。
     */
    public ApiResult<?> dispatch(String path, String method, byte[] body, String bizId, String traceId) {
        // 1. 路由：平台侧路径 → PUBLISHED 接口（40401 接口不存在）
        InterfaceRow iface = interfaceRepository.findByPath(path)
                .orElseThrow(() -> new BizException(40401, "接口不存在：" + path));
        if (!"PUBLISHED".equals(iface.status())) {
            throw new BizException(40401, "接口未发布：" + path);
        }
        if (!iface.method().equalsIgnoreCase(method)) {
            throw new BizException(40401, "接口方法不匹配：" + method + "（期望 " + iface.method() + "）");
        }
        // 2. 应用启用校验（停用即拒，M1 钩子 M2 接入）
        if (!appService.isRequestAllowed(iface.appId())) {
            throw BizException.appDisabled(iface.appId());
        }
        String trace = traceId == null ? UUID.randomUUID().toString().replace("-", "") : traceId;
        String biz = bizId == null || bizId.isBlank() ? UUID.randomUUID().toString() : bizId;
        log.info("路由命中接口 code={} appId={} 供应商={}", iface.code(), iface.appId(), iface.upstreamPath());
        return execute(iface, body, biz, trace);
    }

    // ---------- 状态链批量缓冲（2026-09-18 抽出到 StateChainBuffer：前置执行器要共用同一批量契约） ----------

    private static void beginChain() {
        StateChainBuffer.begin();
    }

    private static void chainAppend(String from, String to, int attempt, String errorCode,
                                    String trigger, String detail) {
        StateChainBuffer.append(from, to, attempt, errorCode, trigger, detail);
    }

    private static java.util.List<OutboundRequestRepository.StateChainNode> endChain() {
        return StateChainBuffer.drain();
    }

    /** 执行出站链路（首送与补偿重放共用入口）。M4：入口填充调用日志上下文（清理契约见 CallLogContext）。
     *  M5 后状态链：传输异常分类已下沉到 doInvoke（设计 §4.6 坑 1：短重试次数须在 endRetryBudget 之前读取）；
     *  状态链节点攒批后在本方法 finally 一次落库（每请求 1 次批量，不拖慢主链路）。 */
    public ApiResult<?> execute(InterfaceRow iface, byte[] body, String bizId, String traceId) {
        CallLogContext.set(iface.id(), iface.appId(), traceId);
        // 前置宿主补偿预算下限（D-PS-11 已拍板：配置了前置的接口强制 ≥1 次补偿尝试）；
        // hasPreSteps 读的是装配缓存链，不额外查库（本次请求随后自会走到 chainEngine.execute）
        long recordId = createRecord(iface, body, bizId, traceId, chainEngine.hasPreSteps(iface.id()));
        beginChain();
        try {
            chainAppend(null, "INIT", 1, null, TRIGGER_FIRST_SEND, "创建出站记录");
            return doInvoke(recordId, iface, body, traceId, false, 1, "INIT");
        } catch (BizException e) {
            // 链失败（D7 不污染状态机）/ 熔断短路 / 死信 / 业务失败 / 传输异常分类：均已按状态机落库或按 D7 不落运行表
            throw e;
        } catch (Exception e) {
            log.error("出站请求 {} 执行异常", recordId, e); // 意外异常保留堆栈
            throw new BizException(50000, "平台内部错误");
        } finally {
            flushChainSafely(recordId, traceId);
        }
    }

    /** 状态链批量落库（失败容忍：只影响可观测数据，不影响主链路结果） */
    private void flushChainSafely(long recordId, String traceId) {
        try {
            outboundRequestRepository.flushStateChain(recordId, traceId, endChain());
        } catch (Exception e) {
            log.warn("状态链批量落库失败 recordId={}", recordId, e);
        }
    }

    // ---------- 首送与重放核心 ----------

    private ApiResult<?> doInvoke(long recordId, InterfaceRow iface, byte[] body, String traceId,
                                  boolean compensate, int attempt, String startStatus) {
        String trigger = compensate ? TRIGGER_COMPENSATE : TRIGGER_FIRST_SEND;
        String how = compensate ? "补偿重放" : "首送";
        // 链执行：入站鉴权 → 解码 → 报文适配 → 前置步骤（编排）→ 字段映射 → 编码 → 出站鉴权
        // 前置失败（PreStepFailure）必须在这里捕获并分类：它由 ChainEngine 的 MAPPING 闭包内的
        // PreStepExecutor 抛出，而本行位于下方 invoker 的 try/catch **之外**（评审 v0.1.2 修正点 ①）——
        // 不捕获则异常直接冒到 execute() 的 catch(BizException) 原样透出，A 停在 INIT，
        // COMPENSATING / UNKNOWN 两个出口永远走不到。
        AdapterContext ctx;
        try {
            ctx = chainEngine.execute(iface.id(), UnifiedModel.emptyObject(), traceId, body,
                    Map.of("attempt", attempt, "preCallDepth", 1));
        } catch (PreStepFailure e) {
            throw classifyPreStepFailure(recordId, e, trigger, how, attempt);
        }

        // 出站规格补全：URL / 方法 / 超时（M0-03 §1.2）+ M4 元数据与 traceId 透传（D-M4-4：
        // X-Trace-Id 平台 → 上游公共头，补齐 M2 缺口；元数据供 OUT 方向 call_log 读取）
        ctx.outbound().url(appOf(iface).baseUrl() + iface.upstreamPath());
        ctx.outbound().method(iface.method());
        ctx.outbound().readTimeoutMs(iface.timeoutMs());
        ctx.outbound().interfaceId(iface.id());
        ctx.outbound().appId(iface.appId());
        ctx.outbound().traceId(traceId);
        if (traceId != null && !traceId.isBlank()) {
            ctx.outbound().header("X-Trace-Id", traceId);
        }

        // 熔断闸门（D-M4-1，置于 @Retryable Invoker 调用之前，M0-03 §1.4）：
        // OPEN 短路——不发起调用、不触发短重试，转 COMPENSATING 顺延（不 incrementAttempt）；
        // 顺延无上限为有意语义（不因上游宕机杀死消息），50202 = 上游熔断短路
        if (!circuitBreakerRegistry.tryAcquire(iface.id())) {
            LocalDateTime next = LocalDateTime.now().plusSeconds(
                    Math.min(circuitBreakerRegistry.retryAfterSeconds(iface.id()), CIRCUIT_DEFER_SECONDS));
            // 首送短路：INIT→COMPENSATING 记节点；补偿轮 from==to（COMPENSATING）同态顺延不记节点
            if (!"COMPENSATING".equals(startStatus)) {
                chainAppend(startStatus, "COMPENSATING", attempt, "50202",
                        TRIGGER_CIRCUIT_OPEN, how + " 熔断 OPEN，顺延补偿（不计数）");
            }
            outboundRequestRepository.updateState(recordId, "COMPENSATING", null, null, next, "50202");
            circuitBreakerRegistry.logState("短路", iface.id(), iface.appId());
            log.warn("出站请求 {} 熔断短路（接口 {} OPEN）→ COMPENSATING 顺延至 {}", recordId, iface.id(), next);
            throw new BizException(50202, "供应商熔断短路（已进入补偿队列）");
        }

        // 状态 MAPPING → 调上游（状态列即时更新；节点攒批出口落库）
        // 诊断字段 out_payload（M0-01 D7 / M0-02「映射结果落 out_payload」）：搭车本次已发生的 UPDATE 写入
        // 「映射 + 协议编码后的出站报文」（= 真正发给供应商的 body）；不额外增加库往返（远程库敏感）。
        // GET / DELETE 无请求体（UpstreamInvoker 不带 body）→ 记 null，避免落一个从未发出的 "{}" 造成误读。
        // 写入走 COALESCE，故语义为「首送映射产物快照」：补偿重放同一记录时不覆盖（重放后的映射产物以
        // 供应商实际收到的报文为准，可用 WireMock/对端日志核对）。
        chainAppend(startStatus, "MAPPING", attempt, null, trigger, how + "链执行");
        outboundRequestRepository.updateState(recordId, "MAPPING", outboundBodyText(iface, ctx), null, null, null);
        UpstreamInvoker.beginRetryBudget(iface.maxRetries());
        ResponseEntity<byte[]> resp;
        try {
            resp = upstreamInvoker.invoke(ctx.outbound());
            // 熔断计数（D-M4-1：每请求一次）：invoke 正常返回 = 2xx 或 4xx 非 429（5xx/429/超时以异常到达 catch）
            circuitBreakerRegistry.record(iface.id(), true);
        } catch (Exception e) {
            // 传输类异常（5xx/429/超时连接）计失败；链失败 / 业务异常不进熔断统计
            if (e instanceof org.springframework.web.client.ResourceAccessException
                    || e instanceof org.springframework.web.client.HttpClientErrorException.TooManyRequests
                    || e instanceof org.springframework.web.client.HttpServerErrorException) {
                circuitBreakerRegistry.record(iface.id(), false);
                // 分类下沉（设计 §4.6 坑 1）：在 endRetryBudget()（finally）之前读短重试次数，
                // 与状态分类同一作用域——否则 finally remove 后 classifyInvokeFailure 读到空，detail 的短重试次数恒 0
                long shortRetries = UpstreamInvoker.retryFailures();
                BizException mapped = classifyInvokeFailure(recordId, e, trigger, how, attempt, shortRetries);
                log.warn("出站请求 {} 传输异常（{} → code {}，短重试 {} 次）", recordId,
                        e.getClass().getSimpleName(), mapped.getCode(), Math.max(0, shortRetries - 1));
                throw mapped;
            }
            throw e; // 意外异常：原样抛给 execute / replay 的 catch 兜底
        } finally {
            UpstreamInvoker.endRetryBudget();
        }
        return classify(recordId, iface, resp, trigger, how, attempt);
    }

    /** 结果分类（M0-03 §2 异常映射表 + C2 业务失败定稿） */
    private ApiResult<?> classify(long recordId, InterfaceRow iface, ResponseEntity<byte[]> resp,
                                  String trigger, String how, int attempt) {
        HttpStatusCode status = resp.getStatusCode();
        byte[] respBody = resp.getBody() == null ? new byte[0] : resp.getBody();
        if (status.is2xxSuccessful()) {
            return handleSuccess(recordId, iface, respBody, trigger, how, attempt);
        }
        // 4xx 非 429 → 死信（不重试；5xx/429 已在 Invoker 内重试，到此即耗尽）
        String reason = "供应商 " + status.value() + "：" + resp.getStatusCode();
        chainAppend("MAPPING", "DEAD_LETTER", attempt, "50201", trigger, how + " 4xx 不重试：" + reason);
        outboundRequestRepository.updateState(recordId, "DEAD_LETTER", null, null, null, "50201");
        outboundRequestRepository.insertDeadLetter("OUTBOUND", recordId, reason, bytesText(respBody));
        long deadLetterId = deadLetterId(recordId);
        throw new BizException(50201, "供应商拒绝（4xx）：" + reason + "，死信编号 " + deadLetterId);
    }

    /** 2xx：信封适配判业务成败（M0-03 定稿 C2：业务失败也记 SUCCESS、业务码透传）；RESP 过滤仅成功路径（D-M3-3）。
     *  判定本身复用 {@link ResponseJudger}（与前置步骤同一实现，2026-09-18 抽取）。 */
    private ApiResult<?> handleSuccess(long recordId, InterfaceRow iface, byte[] respBody, String trigger, String how, int attempt) {
        ResponseJudger.Judged judged = responseJudger.judge(iface, respBody);
        chainAppend("MAPPING", "SUCCESS", attempt, null, trigger, how + " 响应 " + respBody.length + " 字节");
        // resp_payload = 供应商响应【原始字节】文本（不落解码后模型）：JSON 场景与原文一致，XML 场景保留原始 XML，
        // 供监控排查「解码失败/协议选错/RESP 名称写错」时直接比对原文（链失败排查见整体测试方案 §6.6）。
        outboundRequestRepository.updateState(recordId, "SUCCESS", null, bytesText(respBody), null, null);
        log.info("出站请求 {} 成功（响应 {} 字节）", recordId, respBody.length);
        if (!judged.success()) {
            // 业务失败：状态机 SUCCESS（传输层已获明确结果），业务码透传（C2）
            return ApiResult.error(parseCode(judged.code(), 50201),
                    judged.msg() == null ? "供应商业务失败" : judged.msg());
        }
        return ApiResult.ok(toJson(judged.data()));
    }

    /**
     * 补偿重放（CompensationWorker 调用）：同一 outbound_request 记录上按 in_payload 重走链
     * （配置/凭证取最新）；重放安全依赖上游对 biz_id 幂等（ADR 5）。
     * 异常归类与首送共用同一分类器：超时/连接异常 → UNKNOWN 对账（不盲目重试），
     * 429/5xx → COMPENSATING 续期（更新 next_retry_at 继续调度）。
     */
    public void replay(OutboundRequestRow row) {
        InterfaceRow iface = interfaceRepository.findById(row.interfaceId())
                .orElseThrow(() -> BizException.ifaceNotFound(row.interfaceId()));
        // 熔断闸门前置（D-M4-1）：OPEN 时不 incrementAttempt（未触达上游不计尝试，防熔断期间
        // 空转消耗重试预算），只顺延 next_retry_at = min(冷却剩余, 3s)；COMPENSATING→COMPENSATING
        // 为同状态顺延（from==to 不记状态链，仅更新 next_retry_at）
        if (!circuitBreakerRegistry.tryAcquire(row.interfaceId())) {
            LocalDateTime next = LocalDateTime.now().plusSeconds(
                    Math.min(circuitBreakerRegistry.retryAfterSeconds(row.interfaceId()), CIRCUIT_DEFER_SECONDS));
            outboundRequestRepository.updateState(row.id(), "COMPENSATING", null, null, next, "50202");
            log.info("补偿重放 outbound_request {} 熔断短路 → 顺延至 {}（不计数）", row.id(), next);
            return;
        }
        log.info("补偿重放 outbound_request {}（attempt {}/{}）", row.id(), row.attemptCount() + 1, row.maxAttempts());
        outboundRequestRepository.incrementAttempt(row.id());
        int attempt = row.attemptCount() + 1; // increment 后的当前轮尝试计数
        beginChain();
        try {
            ApiResult<?> result = doInvoke(row.id(), iface,
                    row.inPayload() == null ? new byte[0]
                            : row.inPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    row.traceId(), true, attempt, "COMPENSATING");
            log.info("补偿重放 outbound_request {} 结果 code={}", row.id(), result.code());
        } catch (BizException e) {
            // 链失败（D7）与死信 / 业务失败 / 传输异常分类（doInvoke 内已落状态）不重复处理，仅记录
            log.warn("补偿重放 outbound_request {} 失败：{}", row.id(), e.getMessage());
        } catch (Exception e) {
            log.error("补偿重放 outbound_request {} 执行异常", row.id(), e);
        } finally {
            flushChainSafely(row.id(), row.traceId());
        }
    }

    /**
     * 异常 → 状态机统一分类器（M0-03 §2 映射表，首送与补偿重放共用，消除两路分叉）：
     * ResourceAccessException（读超时/连接异常，@Retryable 耗尽后透传）→ UNKNOWN 对账；
     * TooManyRequests / HttpServerErrorException → COMPENSATING（next_retry_at 续期）；
     * 其余 → 50000（状态不动，由调用方记录）。
     * M5 后状态链：仅由 doInvoke 的 catch 调用（分类下沉——保证 shortRetries 在 endRetryBudget 之前可读），
     * 状态归类走 transition（detail 带短重试次数）。
     */
    private BizException classifyInvokeFailure(long recordId, Exception e, String trigger, String how,
                                               int attempt, long shortRetries) {
        int retries = (int) Math.max(0, shortRetries - 1); // 失败调用次数 - 1 = 短重试次数
        if (e instanceof org.springframework.web.client.ResourceAccessException) {
            chainAppend("MAPPING", "UNKNOWN", attempt, "50401", trigger,
                    how + " 读超时/连接异常（短重试 " + retries + " 次），结果待对账");
            outboundRequestRepository.updateState(recordId, "UNKNOWN", null, null, null, "50401");
            log.info("出站请求 {} 结果不确定（读超时/连接异常）→ UNKNOWN 待对账", recordId);
            return new BizException(50401, "供应商超时，结果待对账（UNKNOWN）");
        }
        if (e instanceof org.springframework.web.client.HttpClientErrorException.TooManyRequests) {
            LocalDateTime next = LocalDateTime.now().plusSeconds(3);
            chainAppend("MAPPING", "COMPENSATING", attempt, "42903", trigger,
                    how + " 429 重试耗尽（短重试 " + retries + " 次）");
            outboundRequestRepository.updateState(recordId, "COMPENSATING", null, null, next, "42903");
            log.info("出站请求 {} 重试耗尽（429）→ COMPENSATING，补偿 worker 兜底", recordId);
            return new BizException(50201, "供应商暂时不可用，已进入补偿队列");
        }
        if (e instanceof org.springframework.web.client.HttpServerErrorException) {
            LocalDateTime next = LocalDateTime.now().plusSeconds(3);
            chainAppend("MAPPING", "COMPENSATING", attempt, "50201", trigger,
                    how + " 5xx 重试耗尽（短重试 " + retries + " 次）");
            outboundRequestRepository.updateState(recordId, "COMPENSATING", null, null, next, "50201");
            log.info("出站请求 {} 重试耗尽（5xx）→ COMPENSATING，补偿 worker 兜底", recordId);
            return new BizException(50201, "供应商暂时不可用，已进入补偿队列");
        }
        return new BizException(50000, "平台内部错误");
    }

    // ---------- 私有 ----------

    /**
     * 创建出站记录（status=INIT，首送即第 1 次尝试）。INIT 状态链首条由 execute 的
     * chainAppend(null→INIT) + finally flush 统一批量落库（主路径每请求仅 1 次批量，见 flushStateChain）。
     *
     * <p>补偿预算下限（D-PS-11，2026-09-18 拍板）：配置了前置步骤的宿主取 {@code max(2, maxRetries+1)}——
     * 否则 {@code max_retries=0} 的宿主一旦进 COMPENSATING，首次扫描即判「补偿耗尽」
     * （{@code attempt_count=1 >= max_attempts=1}），零补偿尝试直接死信，§6.4 的
     * 「5xx / 熔断短路顺延」名存实亡。非前置宿主的原语义（max_retries=0 = 零补偿尝试）保持不变。
     */
    private long createRecord(InterfaceRow iface, byte[] body, String bizId, String traceId,
                             boolean hostHasPreSteps) {
        int maxAttempts = hostHasPreSteps
                ? Math.max(2, iface.maxRetries() + 1)
                : iface.maxRetries() + 1;
        return outboundRequestRepository.insert(new OutboundRequestRow(
                0, iface.id(), iface.appId(), bizId,
                body == null ? null : new String(body, java.nio.charset.StandardCharsets.UTF_8),
                null, null, "INIT", 1, // 首送即第 1 次尝试
                maxAttempts, null, null, traceId, null, null));
    }

    /**
     * 前置步骤失败 → 宿主状态机分类（设计方案 §6.4 矩阵；复用既有三条出口，不新增状态 / 错误码）：
     * HTTP_5XX / CIRCUIT_OPEN → COMPENSATING 顺延（50201 / 50202，不 incrementAttempt）；
     * TIMEOUT → UNKNOWN（50401：结果不确定，不可降级为可安全重试）；
     * 其余（CONFIG_ERROR / DEPTH_EXCEEDED / HTTP_4XX / BUSINESS_FAIL）→ 链失败（记录停留 INIT）。
     */
    private BizException classifyPreStepFailure(long recordId, PreStepFailure e,
                                                String trigger, String how, int attempt) {
        String detail = how + " 前置步骤 " + e.getStepCode() + "：" + e.getDetail();
        return switch (e.getKind()) {
            case HTTP_5XX, CIRCUIT_OPEN -> {
                String code = e.getKind() == PreStepFailure.Kind.CIRCUIT_OPEN ? "50202" : "50201";
                LocalDateTime next = LocalDateTime.now().plusSeconds(CIRCUIT_DEFER_SECONDS);
                chainAppend("INIT", "COMPENSATING", attempt, code, trigger, detail);
                outboundRequestRepository.updateState(recordId, "COMPENSATING", null, null, next, code);
                log.warn("出站请求 {} 前置步骤失败 → COMPENSATING 顺延（{}，不计数）", recordId, code);
                yield new BizException("50202".equals(code) ? 50202 : 50201,
                        "前置步骤不可用，已进入补偿队列（" + e.getStepCode() + "）");
            }
            case TIMEOUT -> {
                chainAppend("INIT", "UNKNOWN", attempt, "50401", trigger, detail);
                outboundRequestRepository.updateState(recordId, "UNKNOWN", null, null, null, "50401");
                log.info("出站请求 {} 前置步骤超时 → UNKNOWN 待对账", recordId);
                yield new BizException(50401, "前置步骤超时，结果待对账（UNKNOWN）");
            }
            default -> {
                log.warn("出站请求 {} 前置步骤失败（链失败，记录停留 INIT）：{}", recordId, e.getDetail());
                yield e; // CONFIG_ERROR / DEPTH_EXCEEDED / HTTP_4XX / BUSINESS_FAIL → 40001（不推进状态机）
            }
        };
    }

    private AppRow appOf(InterfaceRow iface) {
        return appRepository.findById(iface.appId()).orElseThrow();
    }

    private long deadLetterId(long recordId) {
        return recordId; // M2 简化：ref_id 即出站记录 id（dead_letter.ref_id 多态引用）
    }

    private String bytesText(byte[] body) {
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** RESP 字段声明已随判定逻辑抽出到 {@link ResponseJudger}（2026-09-18，避免两套判定口径） */

    /**
     * 出站诊断字段 out_payload 取值（M0-01 D7）：映射 + 协议编码后的出站报文文本；
     * GET / DELETE 无请求体 → null（与 UpstreamInvoker 实际行为对齐）；无 body 同样为 null。
     */
    private String outboundBodyText(InterfaceRow iface, AdapterContext ctx) {
        String method = iface.method() == null ? "POST" : iface.method().toUpperCase();
        if ("GET".equals(method) || "DELETE".equals(method)) {
            return null;
        }
        byte[] outBody = ctx.outbound().body();
        return outBody == null ? null : new String(outBody, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** UnifiedModel → JsonNode（D-M3-4 收敛后统一走协议适配器的静态转换；失败返回 null） */
    private JsonNode toJson(UnifiedModel.UNode node) {
        try {
            return node == null ? null : JsonProtocolAdapter.fromUnified(node, objectMapper);
        } catch (Exception e) {
            return null;
        }
    }

    private int parseCode(String code, int def) {
        try {
            return Integer.parseInt(code);
        } catch (Exception e) {
            return def;
        }
    }
}
