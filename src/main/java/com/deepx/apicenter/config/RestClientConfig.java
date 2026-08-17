package com.deepx.apicenter.config;

import com.deepx.apicenter.client.ErpCallbackClient;
import com.deepx.apicenter.client.PartnerAClient;
import com.deepx.apicenter.client.PartnerBClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;

/**
 * RestClient 与 {@code @HttpExchange} 客户端 Bean 装配（设计文档 §2.2「出站客户端」）。
 *
 * <p><b>注意（Spring Boot 4.1）</b>：Boot 4 不再像 3.x 那样自动配置 {@code RestClient.Builder} Bean，
 * 因此这里用 {@link RestClient#builder()} 静态工厂自行构建，不依赖注入的 Builder。
 * 默认转换器会按 classpath 自动探测（含 Jackson3 JSON {@code JacksonJsonHttpMessageConverter} 与
 * XML {@code JacksonXmlHttpMessageConverter}，因 jackson-dataformat-xml 在 classpath）。
 *
 * <p>每个渠道一个客户端：设置 baseUrl / 鉴权头 / 读超时（read-timeout-ms=3000），再经
 * {@link HttpServiceProxyFactory} 生成声明式客户端代理。
 * traceparent 透传由 OpenTelemetry 自动注入 RestClient，无需手写（能力 3.3，设计文档 §7.1）。
 */
@Configuration
@EnableConfigurationProperties(ChannelProperties.class)
public class RestClientConfig {

    @Bean
    PartnerAClient partnerAClient(ChannelProperties props) {
        return createClient(props.getChannels().get("PARTNER_A"), PartnerAClient.class);
    }

    @Bean
    PartnerBClient partnerBClient(ChannelProperties props) {
        return createClient(props.getChannels().get("PARTNER_B"), PartnerBClient.class);
    }

    @Bean
    ErpCallbackClient erpCallbackClient(ChannelProperties props) {
        return createClient(props.getChannels().get("ERP"), ErpCallbackClient.class);
    }

    /**
     * 通用装配：用 {@link RestClient#builder()} 静态工厂构建带超时/鉴权的 RestClient，并生成 HTTP 接口代理。
     *
     * @param ch        渠道配置
     * @param clientType 客户端接口类型（PartnerAClient / PartnerBClient / ErpCallbackClient）
     */
    private <T> T createClient(ChannelProperties.Channel ch, Class<T> clientType) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofMillis(ch.getReadTimeoutMs())); // 读超时 → UNKNOWN 分支

        RestClient restClient = RestClient.builder()
                .baseUrl(ch.getBaseUrl())
                .defaultHeaders(headers -> headers.setBearerAuth(ch.getAuthToken()))
                .requestFactory(factory)
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(clientType);
    }
}
