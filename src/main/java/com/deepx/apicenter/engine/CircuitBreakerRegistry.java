package com.deepx.apicenter.engine;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器注册表（M4 交付，D-M4-1）：按 interface_id 维护熔断器实例（单实例内存态）。
 * OutboundEngine 在调用 UpstreamInvoker 之前过闸门（OPEN 短路不触发 @Retryable）；
 * 入站送达不经本闸门（InboundEngine 直调 Invoker，设计 §6.4 熔断目标为「上游」）。
 * 状态暴露 Micrometer Gauge apicenter.circuit.state{interface}（0/1/2 = CLOSED/OPEN/HALF_OPEN）。
 */
@Component
public class CircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRegistry.class);

    /** 总开关（默认 true；测试可用 circuit.enabled=false 整体关闭） */
    @Value("${app.api-center.circuit.enabled:true}")
    private boolean enabled;

    /** 失败率阈值（0~1，默认 50%） */
    @Value("${app.api-center.circuit.failure-rate-threshold:0.5}")
    private double failureRateThreshold;

    /** 滑动窗口秒数 */
    @Value("${app.api-center.circuit.sliding-window-seconds:10}")
    private long slidingWindowSeconds;

    /** 窗口最小请求数（不足不开闸） */
    @Value("${app.api-center.circuit.minimum-number-of-calls:10}")
    private int minimumNumberOfCalls;

    /** OPEN 冷却时长秒数 */
    @Value("${app.api-center.circuit.open-duration-seconds:30}")
    private long openDurationSeconds;

    /** 半开放行探测数 */
    @Value("${app.api-center.circuit.half-open-probes:2}")
    private int halfOpenProbes;

    private final Map<Long, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public CircuitBreakerRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 闸门判断：放行返回 true；禁用或无实例约束时恒 true */
    public boolean tryAcquire(long interfaceId) {
        if (!enabled) {
            return true;
        }
        return breaker(interfaceId).tryAcquire();
    }

    /** 请求结果计入（allowed 请求 = true 成功 / false 失败；只统计触达过上游的请求） */
    public void record(long interfaceId, boolean success) {
        if (!enabled) {
            return;
        }
        breaker(interfaceId).record(success);
    }

    /** 短路顺延参考秒数（D-M4-1：min(冷却剩余, 3s) 与补偿固定间隔对齐） */
    public long retryAfterSeconds(long interfaceId) {
        if (!enabled) {
            return 3;
        }
        return breaker(interfaceId).retryAfterSeconds();
    }

    /** 状态机转换日志（含接口维度，可观测） */
    public void logState(String context, long interfaceId, String appId) {
        if (!enabled) {
            return;
        }
        CircuitBreaker.State state = breaker(interfaceId).state();
        log.info("熔断状态[{}] interface={} app={} state={}", context, interfaceId, appId, state);
    }

    /** 测试支撑：全部复位（集成测试 @BeforeEach，防 5xx 用例累计失败跨用例开闸） */
    public void resetAll() {
        breakers.values().forEach(CircuitBreaker::reset);
    }

    /** 测试支撑：单接口复位 */
    public void reset(long interfaceId) {
        CircuitBreaker breaker = breakers.get(interfaceId);
        if (breaker != null) {
            breaker.reset();
        }
    }

    public CircuitBreaker.State stateOf(long interfaceId) {
        CircuitBreaker breaker = breakers.get(interfaceId);
        return breaker == null ? CircuitBreaker.State.CLOSED : breaker.state();
    }

    private CircuitBreaker breaker(long interfaceId) {
        return breakers.computeIfAbsent(interfaceId, id -> {
            CircuitBreaker breaker = new CircuitBreaker(slidingWindowSeconds, minimumNumberOfCalls,
                    failureRateThreshold, openDurationSeconds, halfOpenProbes);
            // Gauge 惰性注册（幂等：同名同 tag 只注册一次）
            meterRegistry.gauge("apicenter.circuit.state", Tags.of("interface", String.valueOf(id)),
                    breaker, b -> b.state().ordinal());
            return breaker;
        });
    }
}
