package com.deepx.apicenter.config;

import com.deepx.apicenter.client.ExchangeClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.client.support.RestClientAdapter;

import java.time.Duration;

/**
 * 通用客户端装配（M0-03 §1.3）：Boot 4 不自动装配 RestClient.Builder，
 * 需 RestClient + HttpServiceProxyFactory 手动构建（CLAUDE.md Gotcha）。
 * 超时：全局连接/读超时 3000ms（与 interface.timeout_ms 默认一致）；
 * 接口级 per-request 超时的动态生效列入 M5 调优验证（M0-03 §1.3 验证点）。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public ExchangeClient exchangeClient() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofMillis(3000));
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builder()
                .exchangeAdapter(RestClientAdapter.create(restClient))
                .build();
        return factory.createClient(ExchangeClient.class);
    }
}
