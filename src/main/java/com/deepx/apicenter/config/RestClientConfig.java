package com.deepx.apicenter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 通用客户端装配（M0-03 §1.3）：Boot 4 不自动装配 RestClient.Builder，
 * 需手动构建（CLAUDE.md Gotcha）。
 *
 * <p>M0-03 C1 验证点结论（M2 落地）：Spring 7 声明式客户端（@HttpExchange）的 URI 模板变量
 * 经 DefaultUriBuilderFactory 展开时会被路径编码，完整 URL（含 ://）作为 {url} 变量传入
 * 时 scheme 被编码丢失（URI with undefined scheme）。按 C1 退化方案，改用 RestClient 直调
 * （uri(URI) 不经过模板展开），URL/方法/Header/Body 仍全参数化、按协议维度组装，
 * 契约语义不变（通用单一客户端、不按渠道写死）。
 *
 * <p>超时口径（D-PS-0 修复，2026-09-13）：
 * <ul>
 *   <li><b>读超时 = 接口级 per-request</b>：{@code interface.timeout_ms}（出站 = 调供应商 /
 *       入站 = 调回调地址），由 {@link PerRequestReadTimeoutFactory} 作用域在
 *       {@code UpstreamInvoker.dispatch} 内声明，每次调用（含其内部短重试）按该接口配置生效。
 *       修复前该配置被全局 3000ms 静默吃掉（管理面可配、文档可改、实现不读）；
 *       验证点见《前置接口编排设计方案.md》D-PS-0 与 {@code PerRequestReadTimeoutFactoryTest}。</li>
 *   <li>连接超时 = <b>全局</b> {@code app.api-center.connect-timeout-ms}（默认 3000ms）——经自定义
 *       HttpClient 设置（{@link JdkClientHttpRequestFactory} 无 setConnectTimeout）；缺失连接超时
 *       会导致「连不上的上游」在 TCP 连接阶段无限挂起，读超时永不触发。</li>
 *   <li>未声明作用域（如管理面自调 /test-callback 回环）→ 兜底 {@code app.api-center.default-read-timeout-ms}
 *       （默认 3000ms，与 {@code interface.timeout_ms} 默认值一致）。</li>
 * </ul>
 */
@Configuration
public class RestClientConfig {

    /** 连接超时（全局；per-request 连接超时需 per-request HttpClient，成本高收益低，故保持全局可配） */
    @Value("${app.api-center.connect-timeout-ms:3000}")
    private long connectTimeoutMs;

    /** 兜底读超时（接口未配 / 配置非法 / 未声明作用域时使用） */
    @Value("${app.api-center.default-read-timeout-ms:3000}")
    private long defaultReadTimeoutMs;

    /**
     * 按请求读超时的客户端工厂（D-PS-0）：出站 / 入站送达 / 管理面自调共用同一实例，
     * 读超时由调用方作用域声明（{@code UpstreamInvoker} 是唯一业务声明点）。
     */
    @Bean
    public PerRequestReadTimeoutFactory perRequestReadTimeoutFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        return new PerRequestReadTimeoutFactory(httpClient, Duration.ofMillis(defaultReadTimeoutMs));
    }

    @Bean
    public RestClient restClient(PerRequestReadTimeoutFactory requestFactory) {
        return RestClient.builder()
                .requestFactory(requestFactory)
                // 禁用默认状态异常抛出：4xx/5xx 一律返回 ResponseEntity，
                // 由 OutboundEngine 分类（4xx 死信 / 5xx·429 由 UpstreamInvoker 手动抛触发 @Retryable，M0-03 §2 映射表）
                .defaultStatusHandler(HttpStatusCode::isError,
                        (request, response) -> { /* 不抛，交由引擎分类 */ })
                .build();
    }
}
