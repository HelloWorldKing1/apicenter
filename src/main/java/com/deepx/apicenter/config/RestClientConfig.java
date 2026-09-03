package com.deepx.apicenter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * <p>超时：全局读超时 3000ms（与 interface.timeout_ms 默认一致）；
 * 接口级 per-request 超时的动态生效列入 M5 调优验证（M0-03 §1.3 验证点）。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient() {
        // 连接超时经自定义 HttpClient 设置（JdkClientHttpRequestFactory 无 setConnectTimeout）；
        // 缺失连接超时会导致「连不上的上游」在 TCP 连接阶段无限挂起，读超时永不触发。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(3000))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(3000));
        return RestClient.builder()
                .requestFactory(requestFactory)
                // 禁用默认状态异常抛出：4xx/5xx 一律返回 ResponseEntity，
                // 由 OutboundEngine 分类（4xx 死信 / 5xx·429 由 UpstreamInvoker 手动抛触发 @Retryable，M0-03 §2 映射表）
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError,
                        (request, response) -> { /* 不抛，交由引擎分类 */ })
                .build();
    }
}
