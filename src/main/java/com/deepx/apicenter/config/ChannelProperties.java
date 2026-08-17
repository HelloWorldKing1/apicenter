package com.deepx.apicenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 绑定 {@code application.yaml} 的 {@code app.integration} 配置（设计文档 §12.1 对齐）。
 *
 * <p>字段名用驼峰，Spring Boot 宽松绑定自动匹配 kebab-case：
 * {@code retry-worker-fixed-delay-ms → retryWorkerFixedDelayMs}，
 * {@code signature-tolerance-seconds → signatureToleranceSeconds}。
 * 渠道 Map 的 key 即渠道代码（PARTNER_A / PARTNER_B / ERP）。
 */
@ConfigurationProperties(prefix = "app.integration")
public class ChannelProperties {

    /** @Retryable 最大尝试次数（对齐 max-attempts=5） */
    private int maxAttempts = 5;

    /** 补偿 worker 扫描周期（ms，对齐 retry-worker-fixed-delay-ms=3000） */
    private long retryWorkerFixedDelayMs = 3000;

    /** 签名时间戳容差（秒，对齐 signature-tolerance-seconds=300） */
    private int signatureToleranceSeconds = 300;

    /** 渠道配置表 */
    private Map<String, Channel> channels = new HashMap<>();

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getRetryWorkerFixedDelayMs() { return retryWorkerFixedDelayMs; }
    public void setRetryWorkerFixedDelayMs(long retryWorkerFixedDelayMs) { this.retryWorkerFixedDelayMs = retryWorkerFixedDelayMs; }
    public int getSignatureToleranceSeconds() { return signatureToleranceSeconds; }
    public void setSignatureToleranceSeconds(int signatureToleranceSeconds) { this.signatureToleranceSeconds = signatureToleranceSeconds; }
    public Map<String, Channel> getChannels() { return channels; }
    public void setChannels(Map<String, Channel> channels) { this.channels = channels; }

    /**
     * 单个渠道的连接与鉴权配置。
     */
    public static class Channel {
        private String baseUrl;
        private String authToken;
        private String signatureSecret;
        private int readTimeoutMs = 3000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAuthToken() { return authToken; }
        public void setAuthToken(String authToken) { this.authToken = authToken; }
        public String getSignatureSecret() { return signatureSecret; }
        public void setSignatureSecret(String signatureSecret) { this.signatureSecret = signatureSecret; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }
}
