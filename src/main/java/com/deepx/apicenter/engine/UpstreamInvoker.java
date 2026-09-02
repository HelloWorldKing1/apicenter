package com.deepx.apicenter.engine;

import com.deepx.apicenter.client.ExchangeClient;
import com.deepx.apicenter.client.OutboundRequestSpec;
import org.springframework.http.ResponseEntity;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException.TooManyRequests;
import org.springframework.web.client.HttpServerErrorException;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * 出站短重试 Invoker（M0-03 §1.4：@Retryable 必须放独立 Invoker 类——AOP 自调用不触发代理）。
 * Spring 7 内置 @Retryable（org.springframework.resilience.annotation，退避参数内联、无 @Backoff）；
 * 重试条件：HTTP 5xx、429、连接类 IO（ResourceAccessException）；4xx 非 429 不重试（返回由引擎分类）。
 * 重试次数按接口级 max_retries 动态生效（maxRetriesString + ThreadLocal）。
 * 重试耗尽后透传最后一次的原异常（Spring 7 语义，无 RetryExhaustedException），由 OutboundEngine 分类。
 * 熔断闸门（M4）置于本 Invoker 调用之前（技术架构 §4.5）。
 */
@Component
public class UpstreamInvoker {

    /** 接口级重试次数传递通道（OutboundEngine 调用前设置；默认 4 次重试） */
    public static final ThreadLocal<Long> MAX_RETRIES = ThreadLocal.withInitial(() -> 4L);

    private final ExchangeClient exchangeClient;

    public UpstreamInvoker(ExchangeClient exchangeClient) {
        this.exchangeClient = exchangeClient;
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
            throw new TooManyRequests(resp.getStatusCode(), "上游 429 限流");
        }
        return resp; // 2xx 与 4xx 非 429 直接返回，由 OutboundEngine 分类
    }

    private ResponseEntity<byte[]> dispatch(OutboundRequestSpec spec) {
        HashMap<String, String> headers = new HashMap<>();
        spec.headers().forEach((k, vs) -> vs.forEach(v -> headers.put(k, v)));
        return switch (spec.method() == null ? "POST" : spec.method().toUpperCase()) {
            case "GET" -> exchangeClient.get(spec.url(), headers);
            case "PUT" -> exchangeClient.put(spec.url(), headers, spec.body());
            case "DELETE" -> exchangeClient.delete(spec.url(), headers);
            default -> exchangeClient.post(spec.url(), headers, spec.body());
        };
    }
}
