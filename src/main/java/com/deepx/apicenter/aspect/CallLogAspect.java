package com.deepx.apicenter.aspect;

import com.deepx.apicenter.config.TraceIdFilter;
import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.repository.CallLogRepository.CallLogEntry;
import com.deepx.apicenter.service.GatewayGuard;
import com.deepx.apicenter.worker.CallLogWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 调用日志切面（M4 交付，D-M4-4）：每次业务请求两条 call_log——
 * IN（网关入口：调用方→平台 / 供应商回调→平台）+ OUT（平台→上游 / 平台→回调地址）。
 * 适配器链节点明细不进 call_log（归 OTel span，量控与语义边界）。
 *
 * <p>关键定稿：
 * <ul>
 *   <li>切面顺序（评审定稿）：@Order(HIGHEST_PRECEDENCE) 使本切面位于 @Retryable advisor <b>之外</b>——
 *       每业务请求恰一条 OUT 记录（短重试不逐次落日志）、Timer 时长含全部重试；</li>
 *   <li>元数据来源：OUT 条读 OutboundRequestSpec 扩展字段；IN 条读 CallLogContext（引擎入口填充、
 *       本切面 finally 清理；管理面调试端点 /test、/mock-callback 直调引擎不经网关——有意不落 IN 条，
 *       避免调试流量污染成功率口径，OUT 条照常经 Invoker 切面落库，IN/OUT 条数不配对属预期）；</li>
 *   <li>脱敏先于落库（SensitiveDataMasker），异步批量写（CallLogWriter）；</li>
 *   <li>指标同点埋设：Counter apicenter.gateway.requests{direction,interface,app,outcome} +
 *       Timer apicenter.gateway.latency{direction,interface,app}（publishPercentiles 由 MeterRegistry 配置）。</li>
 * </ul>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CallLogAspect {

    private static final Logger log = LoggerFactory.getLogger(CallLogAspect.class);

    private final CallLogWriter callLogWriter;
    private final SensitiveDataMasker masker;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public CallLogAspect(CallLogWriter callLogWriter, SensitiveDataMasker masker,
                         MeterRegistry meterRegistry, ObjectMapper objectMapper) {
        this.callLogWriter = callLogWriter;
        this.masker = masker;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    // ---------- IN：网关入口（调用方 / 供应商回调 → 平台） ----------

    @Around("execution(* com.deepx.apicenter.controller.GatewayController.gateway(..))")
    public Object aroundGateway(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        HttpServletRequest request = findArg(pjp, HttpServletRequest.class);
        byte[] reqBody = findArg(pjp, byte[].class);
        String reqHeaders = masker.maskHeaders(readHeaders(request));
        String traceFallback = request == null ? null : (String) request.getAttribute(TraceIdFilter.ATTR_TRACE_ID);
        try {
            Object result = pjp.proceed();
            int status = statusOf(result);
            writeIn(start, request, reqBody, reqHeaders, traceFallback, status,
                    respBodyOf(result), outcomeOfInSuccess(result));
            return result;
        } catch (Throwable e) {
            int status = e instanceof BizException biz ? biz.getCode() / 100 : 500;
            String msg = e instanceof BizException biz ? biz.getMessage() : "平台内部错误";
            writeIn(start, request, reqBody, reqHeaders, traceFallback, status,
                    "{\"code\":" + (e instanceof BizException biz ? biz.getCode() : 50000)
                            + ",\"msg\":\"" + escape(msg) + "\",\"data\":null}",
                    outcomeOfIn(status, e));
            throw e;
        } finally {
            // 清理契约（CallLogContext）：网关切面是 IN 方向的唯一清理方——
            // 引擎设置、调用方清理（调试端点在各自 finally 清理）
            CallLogContext.clear();
        }
    }

    private void writeIn(long start, HttpServletRequest request, byte[] reqBody, String reqHeaders,
                         String traceFallback, int status, String respBody, String outcome) {
        CallLogContext.Meta meta = CallLogContext.get();
        long interfaceId = meta == null ? 0 : meta.interfaceId();
        String appId = meta == null ? null : meta.appId();
        String traceId = meta != null ? meta.traceId() : traceFallback;
        String url = request == null ? null : request.getRequestURI();
        String method = request == null ? null : request.getMethod();
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        callLogWriter.offer(new CallLogEntry(
                traceId, spanId(), "IN", interfaceId > 0 ? interfaceId : null, appId,
                url, method, status, latencyMs, reqHeaders,
                masker.maskBody(reqBody), masker.maskBody(respBody)));
        metrics("IN", interfaceId, appId, outcome, start);
    }

    // ---------- OUT：平台 → 上游 / 回调地址 ----------

    @Around("execution(* com.deepx.apicenter.engine.UpstreamInvoker.invoke(..))")
    public Object aroundInvoker(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        com.deepx.apicenter.client.OutboundRequestSpec spec =
                findArg(pjp, com.deepx.apicenter.client.OutboundRequestSpec.class);
        try {
            Object result = pjp.proceed();
            int status = result instanceof ResponseEntity<?> re
                    ? re.getStatusCode().value() : 200;
            writeOut(start, spec, status, respBytesOf(result), null);
            return result;
        } catch (Throwable e) {
            int status = e instanceof BizException biz ? biz.getCode() / 100 : 500;
            writeOut(start, spec, status, null, e);
            throw e;
        }
    }

    private void writeOut(long start, com.deepx.apicenter.client.OutboundRequestSpec spec,
                          int status, byte[] respBody, Throwable error) {
        if (spec == null) {
            return; // 防御：无规格不记录
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        Map<String, String> headers = new TreeMap<>();
        spec.headers().forEach((k, values) -> headers.put(k, values.isEmpty() ? "" : values.get(0)));
        callLogWriter.offer(new CallLogEntry(
                spec.traceId(), spanId(), "OUT",
                spec.interfaceId() > 0 ? spec.interfaceId() : null, spec.appId(),
                spec.url(), spec.method(), status, latencyMs,
                masker.maskHeaders(headers),
                masker.maskBody(spec.body()),
                masker.maskBody(respBody)));
        metrics("OUT", spec.interfaceId(), spec.appId(), outcomeOfOut(status, error), start);
    }

    // ---------- 私有 ----------

    private void metrics(String direction, long interfaceId, String appId, String outcome, long startNanos) {
        String ifaceTag = interfaceId > 0 ? String.valueOf(interfaceId) : "-";
        String appTag = appId == null || appId.isBlank() ? "-" : appId;
        meterRegistry.counter("apicenter.gateway.requests",
                        "direction", direction, "interface", ifaceTag, "app", appTag, "outcome", outcome)
                .increment();
        Timer.builder("apicenter.gateway.latency")
                .tag("direction", direction)
                .tag("interface", ifaceTag)
                .tag("app", appTag)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    /** IN 方向结局分类：0 成功 / 限流配额拒绝 / 5xx 段传输失败 / 其余业务失败 */
    private String outcomeOfIn(int status, Throwable error) {
        if (error instanceof BizException biz) {
            if (biz.getCode() == GatewayGuard.QPS_LIMITED || biz.getCode() == GatewayGuard.DAILY_QUOTA_EXCEEDED
                    || biz.getCode() == GatewayGuard.IP_REJECTED) {
                return "rejected";
            }
            if (biz.getCode() / 1000 == 50) {
                return "transport_fail";
            }
            return "business_fail";
        }
        return status < 400 ? "success" : "business_fail";
    }

    /** OUT 方向结局分类：2xx 成功（上游健康响应）；4xx 非 429 视为上游明确拒绝；异常（5xx/429/超时）传输失败 */
    private String outcomeOfOut(int status, Throwable error) {
        if (error != null) {
            return "transport_fail";
        }
        return status < 400 ? "success" : "upstream_fail";
    }

    private Map<String, String> readHeaders(HttpServletRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, String> headers = new TreeMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private int statusOf(Object result) {
        if (result instanceof ResponseEntity<?> re) {
            return re.getStatusCode().value();
        }
        return 200;
    }

    /** IN 响应体序列化：ApiResult（出站统一信封）走 ObjectMapper；byte[]（入站裸 ack）直取 */
    private String respBodyOf(Object result) {
        if (result instanceof ResponseEntity<?> re && re.getBody() != null) {
            Object body = re.getBody();
            if (body instanceof byte[] bytes) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            try {
                return objectMapper.writeValueAsString(body);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private byte[] respBytesOf(Object result) {
        if (result instanceof ResponseEntity<?> re && re.getBody() instanceof byte[] bytes) {
            return bytes;
        }
        return null;
    }

    /** IN 方向成功响应的结局（信封 code 语义）：0 成功 / 非 0 业务失败或传输失败（按 code 段） */
    private String outcomeOfInSuccess(Object result) {
        if (result instanceof ResponseEntity<?> re && re.getBody() instanceof ApiResult<?> api) {
            if (api.code() == 0) {
                return "success";
            }
            return api.code() / 1000 == 50 ? "transport_fail" : "business_fail";
        }
        return "success"; // 入站裸 ack（2xx 即成功送达回执）
    }

    private String spanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String escape(String msg) {
        return msg == null ? "" : msg.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private <T> T findArg(ProceedingJoinPoint pjp, Class<T> type) {
        for (Object arg : pjp.getArgs()) {
            if (type.isInstance(arg)) {
                return type.cast(arg);
            }
        }
        return null;
    }
}
