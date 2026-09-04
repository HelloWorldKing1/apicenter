package com.deepx.apicenter.engine;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 熔断器（M4 交付，D-M4-1）：三态 CLOSED → OPEN → HALF_OPEN，按 interface_id 粒度
 * （=「接口 + 供应商」，接口归属唯一应用，设计 §6.4）。
 *
 * <p>语义定稿（M4 开发计划 §2.1，2026-09-04 评审）：
 * <ul>
 *   <li>计数口径 = <b>每请求一次</b>：invoke 整体出口计一次成败（@Retryable 内部重试不逐次计数）；
 *       失败 = 5xx / 429 / 超时连接异常；4xx 非 429 与 2xx 计成功（上游健康响应）；链失败不进统计；</li>
 *   <li>OPEN 短路：不发起调用、不触发短重试（闸门在 Invoker 调用之前，M0-03 §1.4）；
 *       短路重放不 incrementAttempt（未触达上游不计尝试），只顺延 next_retry_at；</li>
 *   <li>顺延无上限为有意语义：上游宕机期间消息保留在 COMPENSATING、不耗尽不死信；</li>
 *   <li>HALF_OPEN：冷却结束放行 halfOpenProbes 个探测（走完整路径含短重试），
 *       全部成功 → CLOSED（清空窗口防陈旧失败立即再开闸），任一失败 → OPEN 重新计时；</li>
 *   <li>单实例内存态（多实例各自独立熔断，分布式版本 v1.1）。</li>
 * </ul>
 *
 * <p>线程模型：方法级 synchronized——按接口粒度争用低，简单优先。
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private record Sample(long atMillis, boolean success) {
    }

    private final long windowMillis;
    private final int minimumNumberOfCalls;
    private final double failureRateThreshold;
    private final long openDurationMillis;
    private final int halfOpenProbes;

    private final Deque<Sample> window = new ArrayDeque<>();
    private State state = State.CLOSED;
    private long openUntilMillis;
    private int halfOpenRemaining;
    private int halfOpenSuccesses;

    public CircuitBreaker(long slidingWindowSeconds, int minimumNumberOfCalls,
                          double failureRateThreshold, long openDurationSeconds, int halfOpenProbes) {
        this.windowMillis = slidingWindowSeconds * 1000;
        this.minimumNumberOfCalls = minimumNumberOfCalls;
        this.failureRateThreshold = failureRateThreshold;
        this.openDurationMillis = openDurationSeconds * 1000;
        this.halfOpenProbes = Math.max(1, halfOpenProbes);
    }

    /** 闸门判断：是否放行本次请求（OPEN 且未到冷却结束 → false = 短路） */
    public synchronized boolean tryAcquire() {
        return tryAcquire(System.currentTimeMillis());
    }

    synchronized boolean tryAcquire(long now) {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (now >= openUntilMillis) {
                    state = State.HALF_OPEN;
                    halfOpenRemaining = halfOpenProbes;
                    halfOpenSuccesses = 0;
                    halfOpenRemaining--;
                    return true; // 首个探测
                }
                return false;
            case HALF_OPEN:
            default:
                if (halfOpenRemaining > 0) {
                    halfOpenRemaining--;
                    return true; // 放行少量探测
                }
                return false; // 探测名额已满，其余请求仍短路
        }
    }

    /** 请求结果计入滑动窗口并驱动状态机（allowed=true 请求成功 / false 失败） */
    public synchronized void record(boolean success) {
        record(success, System.currentTimeMillis());
    }

    synchronized void record(boolean success, long now) {
        window.addLast(new Sample(now, success));
        evict(now);
        if (state == State.HALF_OPEN) {
            if (success) {
                halfOpenSuccesses++;
                if (halfOpenSuccesses >= halfOpenProbes) {
                    // 探测全部成功 → 恢复 CLOSED（清空窗口：陈旧失败不应立即再触发开闸）
                    state = State.CLOSED;
                    window.clear();
                }
            } else {
                // 任一探测失败 → 重回 OPEN 重新计时
                toOpen(now);
            }
            return;
        }
        if (state == State.CLOSED) {
            evaluate(now);
        }
        // OPEN 态不应有结果计入（未放行即未触达上游）；防御性忽略
    }

    /** CLOSED 态评估：窗口内请求数达标且失败率超阈值 → OPEN（开闸时刻用传入的 now，与可注入时钟一致） */
    private void evaluate(long now) {
        if (window.size() < minimumNumberOfCalls) {
            return; // 最小请求数不足不开闸（防低流量误熔断）
        }
        long failures = window.stream().filter(s -> !s.success()).count();
        if ((double) failures / window.size() >= failureRateThreshold) {
            toOpen(now);
        }
    }

    private void toOpen(long now) {
        state = State.OPEN;
        openUntilMillis = now + openDurationMillis;
    }

    private void evict(long now) {
        long cutoff = now - windowMillis;
        while (!window.isEmpty() && window.peekFirst().atMillis < cutoff) {
            window.removeFirst();
        }
    }

    /** 短路顺延参考：距冷却结束的秒数（HALF_OPEN 探测满员时按完整冷却时长近似；至少 1 秒） */
    public synchronized long retryAfterSeconds() {
        return retryAfterSeconds(System.currentTimeMillis());
    }

    synchronized long retryAfterSeconds(long now) {
        long remaining = state == State.HALF_OPEN
                ? openDurationMillis
                : Math.max(0, openUntilMillis - now);
        return Math.max(1, (remaining + 999) / 1000);
    }

    public synchronized State state() {
        return state;
    }

    /** 测试支撑：清零复位（集成测试 @BeforeEach 调用，防既有 5xx 用例累计失败污染后续断言） */
    public synchronized void reset() {
        state = State.CLOSED;
        window.clear();
        openUntilMillis = 0;
        halfOpenRemaining = 0;
        halfOpenSuccesses = 0;
    }
}
