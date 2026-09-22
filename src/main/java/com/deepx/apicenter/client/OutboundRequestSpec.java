package com.deepx.apicenter.client;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 出站请求规格（M0-03 §1.2）：链上协议编码填 body、出站鉴权加凭证头，
 * 最终由 OutboundRequestBuilder 组装提交。可原地修改（M0-01 定稿 D2）。
 *
 * <p>M4 契约扩展（D-M4-4，元数据、不改传输语义）：interfaceId / appId / traceId
 * 三个属性供 CallLogAspect（OUT 方向 call_log）读取——出站与入站送达组装 spec 时填充。
 */
public class OutboundRequestSpec {

    private String url;
    private String method;
    private final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
    private byte[] body;
    private int readTimeoutMs = 3000;
    private long interfaceId;
    private String appId;
    private String traceId;

    /** 前置步骤名（编排）：仅 PreStepExecutor 填充，供 OUT 方向 call_log 的 step_code 列与「按步骤筛选」 */
    private String stepCode;

    /**
     * SOAP 版本（B2）：ENCODE 阶段按 xml.type 置位（"1.1" / "1.2"；POX 保持 null）。
     * <p>用途：{@code UpstreamInvoker} 在 5xx 时据此判断"要不要按 SOAP Fault 解析响应体"
     * （只有 SOAP 接口才解析；非 SOAP 接口即使 body 里含 &lt;Fault&gt; 也不误判）。
     */
    private String soapVersion;

    public String soapVersion() {
        return soapVersion;
    }

    public void soapVersion(String soapVersion) {
        this.soapVersion = soapVersion;
    }

    public String url() {
        return url;
    }

    public void url(String url) {
        this.url = url;
    }

    public String method() {
        return method;
    }

    public void method(String method) {
        this.method = method;
    }

    public MultiValueMap<String, String> headers() {
        return headers;
    }

    public void header(String name, String value) {
        headers.add(name, value);
    }

    public byte[] body() {
        return body;
    }

    public void body(byte[] body) {
        this.body = body;
    }

    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    public void readTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    // ---------- M4 元数据扩展（D-M4-4：仅用于调用日志 / 链路关联，不参与传输） ----------

    public long interfaceId() {
        return interfaceId;
    }

    public void interfaceId(long interfaceId) {
        this.interfaceId = interfaceId;
    }

    public String appId() {
        return appId;
    }

    public void appId(String appId) {
        this.appId = appId;
    }

    public String traceId() {
        return traceId;
    }

    public void traceId(String traceId) {
        this.traceId = traceId;
    }

    public String stepCode() {
        return stepCode;
    }

    public void stepCode(String stepCode) {
        this.stepCode = stepCode;
    }
}
