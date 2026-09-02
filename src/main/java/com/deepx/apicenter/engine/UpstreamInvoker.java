package com.deepx.apicenter.engine;

import com.deepx.apicenter.client.OutboundRequestSpec;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException.TooManyRequests;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * 出站短重试 Invoker（M0-03 §1.4：@Retryable 必须放独立 Invoker 类——AOP 自调用不触发代理）。
 * Spring 7 内置 @Retryable（org.springframework.resilience.annotation，退避参数内联、无 @Backoff）；
 * 重试条件：HTTP 5xx、429、连接类 IO（ResourceAccessException）；4xx 非 429 不重试（返回由引擎分类）。
 * 重试次数按接口级 max_retries 动态生效（maxRetriesString + ThreadLocal）。
 * 重试耗尽后透传最后一次的原异常（Spring 7 语义），由 OutboundEngine 分类。
 * 熔断闸门（M4）置于本 Invoker 调用之前（技术架构 §4.5）。
 *
 * <p>实现说明（M0-03 C1 验证点结论）：RestClient 直调、uri(URI) 不经过 URI 模板展开——
 * 声明式客户端（@HttpExchange）对动态完整 URL 的模板变量会做路径编码导致 scheme 丢失。
 */
@Component
public class UpstreamInvoker {

    /** 接口级重试次数传递通道（OutboundEngine 调用前设置；默认 4 次重试） */
    public static final ThreadLocal<Long> MAX_RETRIES = ThreadLocal.withInitial(() -> 4L);

    private final RestClient restClient;

    public UpstreamInvoker(RestClient restClient) {
        this.restClient = restClient;
    }

    /** 供 maxRetriesString SpEL 读取当前接口的重试次数 */
    public long currentMaxRetries() {
        return MAX_RETRIES.get();
    }

    @Retryable(includes = {HttpServerErrorException.class, TooManyRequests.class,
            org.springframework.web.client.ResourceAccessException.class},
            maxRetriesString = "#{@upstreamInvoker.currentMaxRetries()}",
            delay = 200, multiplier = 2, maxDelay = 2000,
            timeUnit = TimeUnit.MILLISECONDS)
    public ResponseEntity<byte[]> invoke(OutboundRequestSpec spec) {
        ResponseEntity<byte[]> resp = dispatch(spec);
        if (resp.getStatusCode().is5xxServerError()) {
            throw new HttpServerErrorException(resp.getStatusCode(), "上游 5xx");
        }
        if (resp.getStatusCode().value() == 429) {
            // Spring 7 子类构造器私有化：经静态工厂创建（按状态码返回 TooManyRequests 实例）
            throw org.springframework.web.client.HttpClientErrorException.create(
                    resp.getStatusCode(), "上游 429 限流", resp.getHeaders(), new byte[0], null);
        }
        return resp; // 2xx 与 4xx 非 429 直接返回，由 OutboundEngine 分类
    }

    /** 通用出站调度：URL / 方法 / Header / Body 全参数化（M0-03 C1 契约语义） */
    private ResponseEntity<byte[]> dispatch(OutboundRequestSpec spec) {
        String method = spec.method() == null ? "POST" : spec.method().toUpperCase();
        // GET / DELETE 不带 body（RestClient 对 GET 带 body 的兼容性保守处理）
        if ("GET".equals(method) || "DELETE".equals(method)) {
            return restClient.method(HttpMethod.valueOf(method))
                    .uri(URI.create(spec.url()))
                    .headers(h -> h.addAll(spec.headers()))
                    .retrieve()
                    .toEntity(byte[].class);
        }
        return restClient.method(HttpMethod.valueOf(method))
                .uri(URI.create(spec.url()))
                .headers(h -> h.addAll(spec.headers()))
                .body(spec.body())
                .retrieve()
                .toEntity(byte[].class);
    }
}
