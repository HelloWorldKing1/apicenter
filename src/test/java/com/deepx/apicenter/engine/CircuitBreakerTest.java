package com.deepx.apicenter.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CircuitBreaker 单测（M4 计划 §4）：三态流转 / 最小请求数 / 计数口径 / 窗口衰减 /
 * 半开探测 / 复位。时间参数由长整型毫秒注入（不依赖真实时钟，稳定可测）。
 */
class CircuitBreakerTest {

    /** 默认测试参数：窗口 10s、最小 10 次、失败率 50%、OPEN 30s、半开探测 2 */
    private CircuitBreaker breaker() {
        return new CircuitBreaker(10, 10, 0.5, 30, 2);
    }

    @Test
    void 失败率达标且请求数达标_转OPEN() {
        CircuitBreaker cb = breaker();
        long t = 1_000_000;
        for (int i = 0; i < 10; i++) {
            cb.record(i % 2 == 0, t + i); // 5 成功 5 失败 = 50% ≥ 阈值
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 请求数不足不开闸() {
        CircuitBreaker cb = breaker();
        cb.record(false, 1_000_000);
        cb.record(false, 1_000_001);
        cb.record(false, 1_000_002); // 全失败但只有 3 次 < 最小 10 次
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.tryAcquire()).isTrue();
    }

    @Test
    void OPEN时短路拒绝放行() {
        CircuitBreaker cb = breaker();
        long t = 1_000_000;
        for (int i = 0; i < 10; i++) {
            cb.record(false, t + i);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.tryAcquire(t + 1000)).isFalse(); // 冷却未到，短路
    }

    @Test
    void 冷却结束进入半开并放行探测() {
        CircuitBreaker cb = breaker();
        long t = 1_000_000;
        for (int i = 0; i < 10; i++) {
            cb.record(false, t + i);
        }
        assertThat(cb.tryAcquire(t + 31_000)).isTrue(); // 冷却（30s）结束 → HALF_OPEN 首个探测
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(cb.tryAcquire(t + 31_001)).isTrue(); // 第二个探测（halfOpenProbes=2）
        assertThat(cb.tryAcquire(t + 31_002)).isFalse(); // 探测名额满，其余短路
    }

    @Test
    void 半开探测全部成功_恢复CLOSED且清空窗口() {
        CircuitBreaker cb = breaker();
        long t = 1_000_000;
        for (int i = 0; i < 10; i++) {
            cb.record(false, t + i);
        }
        assertThat(cb.tryAcquire(t + 31_000)).isTrue();
        cb.record(true, t + 31_100); // 探测 1 成功
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(cb.tryAcquire(t + 31_200)).isTrue();
        cb.record(true, t + 31_300); // 探测 2 成功 → CLOSED
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        // 窗口已清空：仅 1 次失败不会立即再开闸（最小请求数保护）
        cb.record(false, t + 32_000);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 半开探测任一失败_重回OPEN重新计时() {
        CircuitBreaker cb = breaker();
        long t = 1_000_000;
        for (int i = 0; i < 10; i++) {
            cb.record(false, t + i);
        }
        assertThat(cb.tryAcquire(t + 31_000)).isTrue();
        cb.record(true, t + 31_100); // 探测 1 成功
        assertThat(cb.tryAcquire(t + 31_200)).isTrue();
        cb.record(false, t + 31_300); // 探测 2 失败 → 重回 OPEN
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.tryAcquire(t + 32_000)).isFalse(); // 重新计时的冷却未到
        assertThat(cb.tryAcquire(t + 61_400)).isTrue();  // 新冷却 30s 后再探测
    }

    @Test
    void 窗口滑动_陈旧失败随时间淘汰() {
        CircuitBreaker cb = breaker();
        long t = 1_000_000;
        for (int i = 0; i < 10; i++) {
            cb.record(false, t + i); // 10 连败 → OPEN
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        // 复位后模拟时间流逝：旧失败滑出窗口（窗口 10s），新请求全部成功
        cb.reset();
        for (int i = 0; i < 20; i++) {
            cb.record(true, t + 60_000 + i);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 成功请求不累计失败率() {
        CircuitBreaker cb = breaker();
        long t = 1_000_000;
        for (int i = 0; i < 50; i++) {
            cb.record(true, t + i); // 50 成功 0 失败
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.tryAcquire()).isTrue();
    }

    @Test
    void 失败率未达阈值不开闸() {
        CircuitBreaker cb = breaker();
        long t = 1_000_000;
        // 10 次中 4 次失败 = 40% < 50%
        for (int i = 0; i < 10; i++) {
            cb.record(i >= 4, t + i);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 复位_清零一切状态() {
        CircuitBreaker cb = breaker();
        long t = 1_000_000;
        for (int i = 0; i < 10; i++) {
            cb.record(false, t + i);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        cb.reset();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.tryAcquire()).isTrue();
        assertThat(cb.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void 短路顺延秒数_冷却内为剩余值且至少1秒() {
        CircuitBreaker cb = breaker();
        long t = 1_000_000;
        for (int i = 0; i < 10; i++) {
            cb.record(false, t + i); // 最后失败时刻 t+9 → openUntil = t+9+30s
        }
        assertThat(cb.retryAfterSeconds(t + 10_000)).isBetween(19L, 21L); // 剩余约 20s
        assertThat(cb.retryAfterSeconds(t + 40_000)).isEqualTo(1);       // 冷却已过 → 下限 1
    }
}
