package com.deepx.apicenter.service;

import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AppRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GatewayGuard 单测（M4 计划 §4）：QPS 超限 / 日配额 / IP 黑白名单 / trust-xff 两态 / 拒绝语义。
 * QPS 固定窗口依赖真实时钟的秒级滚动，同一测试方法内的请求视为同窗口（确定性）。
 */
class GatewayGuardTest {

    private final GatewayGuard guard = new GatewayGuard();

    private AppRow app(Integer qpsLimit, Long dailyQuota, String whitelist, String blacklist) {
        return new AppRow("GUARD-APP", "防护演示", "ops",
                null, null, null, "http://localhost", whitelist, blacklist,
                qpsLimit, dailyQuota, "ENABLED", null, null, null, 0, 0);
    }

    @Test
    void 无限制配置_全部放行() {
        AppRow app = app(null, null, null, null);
        assertThatCode(() -> guard.check(app, "1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    void QPS超限_42901() {
        AppRow app = app(2, null, null, null);
        guard.check(app, "1.2.3.4");
        guard.check(app, "1.2.3.4");
        assertThatThrownBy(() -> guard.check(app, "1.2.3.4"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(42901));
    }

    @Test
    void QPS为0或负_不限流() {
        AppRow app = app(0, 0L, null, null);
        for (int i = 0; i < 10; i++) {
            assertThatCode(() -> guard.check(app, "1.2.3.4")).doesNotThrowAnyException();
        }
    }

    @Test
    void 日配额超限_42902() {
        AppRow app = app(null, 2L, null, null);
        guard.check(app, "1.2.3.4");
        guard.check(app, "1.2.3.4");
        assertThatThrownBy(() -> guard.check(app, "1.2.3.4"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(42902));
    }

    @Test
    void IP黑名单命中_40103_且优先于白名单() {
        AppRow app = app(null, null, "5.6.7.8", "1.2.3.4");
        assertThatThrownBy(() -> guard.check(app, "1.2.3.4"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(40103));
    }

    @Test
    void 白名单非空未命中_40103() {
        AppRow app = app(null, null, "5.6.7.8, 9.10.11.12", null);
        assertThatThrownBy(() -> guard.check(app, "1.2.3.4"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(40103));
        // 命中白名单放行（含逗号分隔多值与空白容忍）
        assertThatCode(() -> guard.check(app, "9.10.11.12")).doesNotThrowAnyException();
    }

    @Test
    void 白名单为空_不限IP() {
        AppRow app = app(null, null, "  ", null);
        assertThatCode(() -> guard.check(app, "1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    void 来源地址_默认remoteAddr_不信任XFF() {
        // trust-xff 默认 false：伪造 X-Forwarded-For 不生效（防绕过白名单）
        assertThat(guard.resolveClientIp("1.2.3.4", "5.6.7.8")).isEqualTo("1.2.3.4");
    }

    @Test
    void 复位_清空限流与配额计数() {
        AppRow app = app(1, 1L, null, null);
        guard.check(app, "1.2.3.4"); // QPS 1 已耗尽
        assertThatThrownBy(() -> guard.check(app, "1.2.3.4")).isInstanceOf(BizException.class);
        guard.reset();
        assertThatCode(() -> guard.check(app, "1.2.3.4")).doesNotThrowAnyException();
    }
}
