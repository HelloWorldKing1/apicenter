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
}
