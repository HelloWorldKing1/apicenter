package com.deepx.apicenter.exception;

/**
 * SOAP 客户端类 Fault（B2：《B2完整SOAP开发计划.md》§2.4 约束 ①）。
 *
 * <p>语义：<b>「我们请求错了」</b>——参数 / 格式 / 权限问题，或版本不匹配（{@code VersionMismatch}）、
 * Header 无法理解（{@code MustUnderstand}）。属<b>确定性错误</b>：重试与补偿毫无意义。
 * 处理：→ 死信（{@code 50203}）、<b>不重试</b>、<b>不计熔断失败</b>（否则会把供应商误判为不健康并熔断）。
 *
 * <p><b>⚠️ 必须直接继承 {@link RuntimeException}，绝不能继承 {@code HttpServerErrorException}</b>：
 * {@code UpstreamInvoker} 上标着
 * {@code @Retryable(includes = {HttpServerErrorException.class, TooManyRequests.class, ResourceAccessException.class})}，
 * 该 includes 是<b>白名单收窄</b>（默认空数组，已 javap 验证）。若本异常继承 {@code HttpServerErrorException}，
 * 会因 {@code instanceof} <b>命中白名单 → 继续短重试</b>，“客户端错误不重试”会静默失效；
 * 同时它还会被 {@code OutboundEngine} 的熔断计数分支误计为失败（双重错）。
 * 回归用例：{@code SoapClientFaultExceptionTest} 钉住类型层次。
 *
 * <p>字段用途：{@code faultCode}/{@code faultMessage} 落死信 reason（截断后，与告警）；
 * {@code rawBody} = 供应商原始响应体（落 {@code dead_letter.payload} 取证，与既有 4xx 死信口径一致）。
 */
public class SoapClientFaultException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String soapVersion;
    private final String faultCode;
    private final String faultMessage;
    private final byte[] rawBody;

    public SoapClientFaultException(String soapVersion, String faultCode, String faultMessage, byte[] rawBody) {
        super("SOAP Fault(" + soapVersion + ") " + faultCode + "：" + faultMessage);
        this.soapVersion = soapVersion;
        this.faultCode = faultCode;
        this.faultMessage = faultMessage;
        this.rawBody = rawBody;
    }

    public String soapVersion() {
        return soapVersion;
    }

    public String faultCode() {
        return faultCode;
    }

    public String faultMessage() {
        return faultMessage;
    }

    /** 供应商原始响应体（取证用；可能为 null） */
    public byte[] rawBody() {
        return rawBody;
    }
}
