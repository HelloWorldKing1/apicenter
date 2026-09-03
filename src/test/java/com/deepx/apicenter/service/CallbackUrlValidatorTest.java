package com.deepx.apicenter.service;

import com.deepx.apicenter.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF 校验单测（M3 计划 §4）：内网 / 回环 / 链路本地地址拒绝、callback-allow-private 开关放行。
 */
class CallbackUrlValidatorTest {

    private final CallbackUrlValidator strict = new CallbackUrlValidator(false); // 生产：拒绝内网
    private final CallbackUrlValidator permissive = new CallbackUrlValidator(true); // 开发 / 测试：放行

    @Test
    void 生产模式拒绝内网与回环() {
        assertThatThrownBy(() -> strict.validateForSave("http://192.168.1.10:8080/cb"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40001));
        assertThatThrownBy(() -> strict.validateForSave("http://10.0.0.1/cb"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> strict.validateForSave("http://127.0.0.1:18080/cb"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> strict.validateForSave("http://localhost:18080/cb"))
                .isInstanceOf(BizException.class);
        assertThat(strict.isAllowed("http://192.168.1.10/cb")).isFalse();
    }

    @Test
    void 生产模式放行公网地址() {
        assertThatCode(() -> strict.validateForSave("https://example.com/cb")).doesNotThrowAnyException();
        assertThat(strict.isAllowed("https://example.com/cb")).isTrue();
    }

    @Test
    void 开关放行回环() {
        assertThatCode(() -> permissive.validateForSave("http://localhost:18080/delivery-ok"))
                .doesNotThrowAnyException();
        assertThat(permissive.isAllowed("http://localhost:18080/delivery-ok")).isTrue();
    }

    @Test
    void 非http协议拒绝() {
        assertThatThrownBy(() -> strict.validateForSave("ftp://example.com/cb"))
                .isInstanceOf(BizException.class);
        assertThat(strict.isAllowed("ftp://example.com/cb")).isFalse();
        assertThat(strict.isAllowed(null)).isFalse();
        assertThat(strict.isAllowed("not-a-url")).isFalse();
    }
}
