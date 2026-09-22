package com.deepx.apicenter.exception;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SoapClientFaultException} 的**类型层次契约**（B2 §2.4 约束 ①）—— 这是最容易静默出错的一处：
 *
 * <p>{@code UpstreamInvoker.invoke} 上标着
 * {@code @Retryable(includes = {HttpServerErrorException.class, TooManyRequests.class, ResourceAccessException.class})}，
 * 而 Spring 7 的 {@code includes} 默认是**空数组**（已 javap 验证）⇒ 该列表是**白名单收窄**，
 * “不在列表里 = 不重试”正是本异常“客户端错误不重试”的实现基础。
 *
 * <p>⚠️ 但白名单匹配是 {@code instanceof} 语义：**一旦本异常继承 {@code HttpServerErrorException}**，
 * 它就会命中白名单而**继续短重试**（设计静默失效）；同时还会被 {@code OutboundEngine} 的熔断计数分支
 * 误计为失败（双重错）。故用本测试把“必须直接继承 RuntimeException”钉死。
 */
class SoapClientFaultExceptionTest {

    @Test
    void 必须直接继承RuntimeException_不得继承HttpServerErrorException() {
        Class<?> superClass = SoapClientFaultException.class.getSuperclass();

        assertThat(superClass).isEqualTo(RuntimeException.class);
        assertThat(HttpServerErrorException.class.isAssignableFrom(SoapClientFaultException.class))
                .as("若为 true 会命中 @Retryable(includes=…) 白名单 → 继续重试，客户端错误不重试的设计失效")
                .isFalse();
    }

    @Test
    void 携带Fault三要素与原始响应体() {
        byte[] raw = "<soap:Fault/>".getBytes();
        SoapClientFaultException e = new SoapClientFaultException("1.2", "soap:Sender", "bad params", raw);

        assertThat(e.soapVersion()).isEqualTo("1.2");
        assertThat(e.faultCode()).isEqualTo("soap:Sender");
        assertThat(e.faultMessage()).isEqualTo("bad params");
        assertThat(e.rawBody()).isEqualTo(raw);
        assertThat(e.getMessage()).contains("1.2").contains("soap:Sender").contains("bad params");
    }
}
