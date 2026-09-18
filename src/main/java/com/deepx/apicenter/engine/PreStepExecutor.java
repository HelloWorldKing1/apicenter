package com.deepx.apicenter.engine;

import com.deepx.apicenter.client.OutboundRequestSpec;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AppRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 前置步骤执行器（前置编排 PS-4；设计方案 §6.2）。
 *
 * <p>刻意做成 {@code OutboundEngine.doInvoke} 的「瘦身版」：**只复用链与传输，绝不进入宿主的运行状态机**——
 * 宿主的 {@code outbound_request} 记录由 {@code OutboundEngine} 维护；前置调用**不落子记录**，
 * 因此不会产生被补偿 worker 独立重扫的「孤儿 COMPENSATING」。这也是不直接复用
 * {@code OutboundEngine.execute} 的原因（那会连带重置状态链缓冲、覆盖 CallLogContext、冲掉宿主重试预算、
 * 并落下可被 worker 独立重放的子记录）。
 *
 * <p>复用的能力：B 自己的链（协议 / 报文适配 / 字段映射 / 出站凭证注入）、短重试预算、熔断器、
 * {@link UpstreamInvoker} 的按请求读超时（D-PS-0）、call_log OUT 与 traceId 贯穿。
 *
 * <p>失败分类由 {@link PreStepFailure} 承载，宿主侧统一走既有三条出口（见该类注释）。
 */
@Component
public class PreStepExecutor {

    private static final Logger log = LoggerFactory.getLogger(PreStepExecutor.class);

    /** 运行期嵌套深度上限（与保存期「解析链长度 ≤ 3」对齐；防手工改库绕过校验） */
    public static final int MAX_PRE_DEPTH = 3;

    /** 留痕 detail 里的片段长度上限（state_log.detail 为 VARCHAR(500)） */
    private static final int DETAIL_SNIPPET = 120;

    private final InterfaceRepository interfaceRepository;
    private final AppRepository appRepository;
    private final ChainEngine chainEngine;
    private final UpstreamInvoker upstreamInvoker;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ResponseJudger responseJudger;

    public PreStepExecutor(InterfaceRepository interfaceRepository,
                           AppRepository appRepository,
                           ChainEngine chainEngine,
                           UpstreamInvoker upstreamInvoker,
                           CircuitBreakerRegistry circuitBreakerRegistry,
                           ResponseJudger responseJudger) {
        this.interfaceRepository = interfaceRepository;
        this.appRepository = appRepository;
        this.chainEngine = chainEngine;
        this.upstreamInvoker = upstreamInvoker;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.responseJudger = responseJudger;
    }

    /**
     * 按 seq 串行执行宿主配置的前置步骤（由 ChainEngine 的 MAPPING 闭包在字段映射之前调用）。
     * 成功一步即把其结果合入 {@code payload.steps.<stepCode>}（宿主的映射 / condition 随后可引用）。
     */
    public void execute(AdapterContext parent, List<InterfaceRow.StepView> steps) {
        String traceId = parent.trace() == null ? null : parent.trace().traceId();
        int depth = parent.attrs().get("preCallDepth") instanceof Integer d ? d : 1;
        int attempt = parent.attrs().get("attempt") instanceof Integer a ? a : 1;
        if (depth >= MAX_PRE_DEPTH) {
            throw new PreStepFailure(PreStepFailure.Kind.DEPTH_EXCEEDED, BizException.FIELD_INVALID, null,
                    "前置链深度超限（" + depth + " ≥ " + MAX_PRE_DEPTH + "），疑似嵌套环");
        }
        for (InterfaceRow.StepView step : steps) {
            if (!step.enabled()) {
                continue;
            }
            executeOne(parent, step, traceId, depth, attempt);
        }
    }

    // ---------- 单步 ----------

    private void executeOne(AdapterContext parent, InterfaceRow.StepView step, String traceId,
                            int depth, int attempt) {
        // 1. 目标可用性（保存期已校验；此处兜底拦截「保存后被下线/删除/改类型」）
        InterfaceRow target = interfaceRepository.findById(step.targetInterfaceId()).orElse(null);
        if (target == null) {
            throw fail(step, traceId, attempt, PreStepFailure.Kind.CONFIG_ERROR, BizException.FIELD_INVALID,
                    "目标接口不存在（id=" + step.targetInterfaceId() + "），可能已被删除", 0, 0);
        }
        if (!"OUTBOUND".equals(target.ifType())) {
            throw fail(step, traceId, attempt, PreStepFailure.Kind.CONFIG_ERROR, BizException.FIELD_INVALID,
                    "目标接口非出站中转（if_type=" + target.ifType() + "）", 0, 0);
        }
        if (!"PUBLISHED".equals(target.status())) {
            throw fail(step, traceId, attempt, PreStepFailure.Kind.CONFIG_ERROR, BizException.FIELD_INVALID,
                    "目标接口未发布（status=" + target.status() + "）", 0, 0);
        }
        AppRow targetApp = appRepository.findById(target.appId()).orElse(null);
        if (targetApp == null || targetApp.baseUrl() == null || targetApp.baseUrl().isBlank()) {
            throw fail(step, traceId, attempt, PreStepFailure.Kind.CONFIG_ERROR, BizException.FIELD_INVALID,
                    "目标接口归属应用缺失或未配置服务地址（base_url）", 0, 0);
        }

        // 2. 入参：宿主当前模型（剥离保留键 → B 看不到宿主已有的步骤输出）交给 B 的链；
        //    preDecoded=true → B 的 DECODE 跳过（模型已就绪，A/B 协议不同也无妨）
        UnifiedModel targetIn = ReservedKeys.withoutSteps(parent.payload());
        AdapterContext targetCtx = chainEngine.execute(target.id(), targetIn, traceId, null, Map.of(
                "preDecoded", true,
                "stepCode", step.stepCode(),
                "invocationRole", "PRE",
                "parentInterfaceId", parent.iface().id(),
                "preCallDepth", depth + 1));   // ★ 必传：否则 B 链内的嵌套前置不计深，运行期防护失效

        // 3. 出站规格：B 自己的应用地址 + B 的路径 / 方法 / 超时（凭证由 B 的链在 OUTBOUND_AUTH 注入）
        OutboundRequestSpec spec = targetCtx.outbound();
        spec.url(targetApp.baseUrl() + target.upstreamPath());
        spec.method(target.method());
        spec.readTimeoutMs(target.timeoutMs());
        spec.interfaceId(target.id());
        spec.appId(target.appId());
        spec.traceId(traceId);
        if (traceId != null && !traceId.isBlank()) {
            spec.header("X-Trace-Id", traceId);
        }

        // 4. 熔断：按 B 的 interface_id（短路不触达上游 → 宿主持顺延，见 PreStepFailure）
        if (!circuitBreakerRegistry.tryAcquire(target.id())) {
            circuitBreakerRegistry.logState("前置短路", target.id(), target.appId());
            throw fail(step, traceId, attempt, PreStepFailure.Kind.CIRCUIT_OPEN, 50202,
                    "目标接口熔断 OPEN（已短路，未触达上游）", 0, 0);
        }

        // 5. 调用（预算 save/restore：endRetryBudget 是 remove() 语义，不恢复会冲掉宿主的预算）
        ResponseEntity<byte[]> resp;
        long start = System.currentTimeMillis();
        UpstreamInvoker.Budget savedBudget = UpstreamInvoker.saveBudget();
        try {
            UpstreamInvoker.beginRetryBudget(target.maxRetries());
            resp = upstreamInvoker.invoke(spec);
            // 计数口径与宿主一致：invoke 正常返回（2xx 或 4xx 非 429）计成功，5xx/429/超时计失败
            circuitBreakerRegistry.record(target.id(), true);
        } catch (ResourceAccessException e) {
            circuitBreakerRegistry.record(target.id(), false);
            long ms = System.currentTimeMillis() - start;
            throw fail(step, traceId, attempt, PreStepFailure.Kind.TIMEOUT, 50401,
                    "读超时 / 连接异常（" + ms + "ms）：结果不确定", ms, 0);
        } catch (HttpServerErrorException | HttpClientErrorException.TooManyRequests e) {
            circuitBreakerRegistry.record(target.id(), false);
            long ms = System.currentTimeMillis() - start;
            throw fail(step, traceId, attempt, PreStepFailure.Kind.HTTP_5XX, 50201,
                    "短重试耗尽（" + e.getClass().getSimpleName() + "，" + ms + "ms）", ms, 0);
        } catch (Exception e) {
            // 非传输类异常（配置 / 编码等）：按链失败处理，避免在 COMPENSATING 里无限循环
            long ms = System.currentTimeMillis() - start;
            throw fail(step, traceId, attempt, PreStepFailure.Kind.CONFIG_ERROR, BizException.FIELD_INVALID,
                    "调用异常（" + e.getClass().getSimpleName() + "）：" + e.getMessage(), ms, 0);
        } finally {
            UpstreamInvoker.restoreBudget(savedBudget);
        }
        long elapsedMs = System.currentTimeMillis() - start;
        int httpStatus = resp.getStatusCode().value();
        byte[] body = resp.getBody() == null ? new byte[0] : resp.getBody();

        // 6. 非 2xx（4xx 非 429）→ 上游明确拒绝 → 宿主导「链失败」出口（不推进状态机、不建死信）
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw fail(step, traceId, attempt, PreStepFailure.Kind.HTTP_4XX, BizException.FIELD_INVALID,
                    "上游拒绝（HTTP " + httpStatus + "）：" + snippet(bodyText(body)), elapsedMs, httpStatus);
        }

        // 7. 判定：复用与宿主完全相同的「信封 + RESP 白名单」判定路径
        ResponseJudger.Judged judged = responseJudger.judge(target, body);
        if (!judged.success()) {
            throw fail(step, traceId, attempt, PreStepFailure.Kind.BUSINESS_FAIL, BizException.FIELD_INVALID,
                    "业务失败（HTTP " + httpStatus + "，code=" + judged.code()
                            + "：" + snippet(judged.msg()) + "）", elapsedMs, httpStatus);
        }

        // 8. 合入宿主模型命名空间（字面量键，绝不做点路径解析）+ 步骤留痕 + 调试留痕
        ReservedKeys.putStep(parent.payload(), step.stepCode(), judged.data());
        appendNode(attempt, errorCodeOf(null),
                step.stepCode() + " HTTP " + httpStatus + " " + elapsedMs + "ms code=" + judged.code());
        PreStepTrace.add(traceId, new PreStepTrace.Item(step.stepCode(), target.code(),
                step.failurePolicy(), httpStatus, elapsedMs, "SUCCESS"));
        log.info("前置步骤 {}.{} → {} HTTP {} {}ms（结果合入 steps.{}）", parent.iface().code(),
                step.stepCode(), target.code(), httpStatus, elapsedMs, step.stepCode());
    }

    // ---------- 私有 ----------

    /**
     * 失败统一出口：留痕节点 + 调试留痕 + 返回 PreStepFailure（调用方 throw）。
     *
     * @param errorCode 留痕节点的 error_code（= 宿主侧出口所用的错误码）
     */
    private PreStepFailure fail(InterfaceRow.StepView step, String traceId, int attempt,
                                PreStepFailure.Kind kind, int code, String detail,
                                long latencyMs, int httpStatus) {
        appendNode(attempt, errorCodeOf(code), step.stepCode() + " " + detail);
        PreStepTrace.add(traceId, new PreStepTrace.Item(step.stepCode(), step.targetCode(),
                step.failurePolicy(), httpStatus, latencyMs, String.valueOf(code)));
        log.warn("前置步骤 {} 失败（kind={}）：{}", step.stepCode(), kind, detail);
        return new PreStepFailure(kind, code, step.stepCode(), detail);
    }

    /**
     * 步骤留痕节点：{@code from=to=INIT} + {@code trigger=PRE_STEP}（走批量通道，不校验 from==to）。
     * 用 INIT 而非 MAPPING 是刻意的：此刻宿主的 status 列**尚未**进入 MAPPING（那是链执行完才 update），
     * 写 MAPPING 会让状态链谎报一次未发生的转移。
     */
    private void appendNode(int attempt, String errorCode, String detail) {
        StateChainBuffer.append("INIT", "INIT", attempt, errorCode, "PRE_STEP", detail);
    }

    private String errorCodeOf(Integer code) {
        return code == null ? null : String.valueOf(code);
    }

    private String bodyText(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= DETAIL_SNIPPET ? trimmed : trimmed.substring(0, DETAIL_SNIPPET) + "…";
    }
}
