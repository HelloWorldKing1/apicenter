package com.deepx.apicenter.engine;

import com.deepx.apicenter.client.ExchangeClient;
import com.deepx.apicenter.client.OutboundRequestSpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.HashMap;

/**
 * 出站短重试 Invoker（M0-03 §1.4：@Retryable 必须放独立 Invoker 类——AOP 自调用不触发代理）。
 * 重试条件：HTTP 5xx、429、连接类 IO（ResourceAccessException）；4xx 非 429 不重试（返回由引擎分类）。
 * 重试次数按接口级 max_retries 动态生效（maxAttemptsExpression + ThreadLocal，M0-03 §1.4）。
 * 熔断闸门（M4）置于本 Invoker 调用之前（技术架构 §4.5）。
 */
@Component
public class UpstreamInvoker {

    /** 接口级重试上限的传递通道（OutboundEngine 调用前设置；默认 4 次重试 + 1 次首送） */
    public static final ThreadLocal<Integer> MAX_ATTEMPTS = ThreadLocal.withInitial(() -> 5);

    private final ExchangeClient exchangeClient;

    public UpstreamInvoker(ExchangeClient exchangeClient) {
        this.exchangeClient = exchangeClient;
    }

    /** 供 maxAttemptsExpression 读取当前接口的重试上限 */
    public int currentMaxAttempts() {
        return MAX_ATTEMPTS.get();
    }

    @Retryable(retryOn = {HttpServerErrorException.class, ResourceAccessException.class,
            org.springframework.web.client.HttpClientErrorException.TooManyRequests.class},
            maxAttemptsExpression = "#{@upstreamInvoker.currentMaxAttempts()}",
            backoff = @Backoff(delay = 200, multiplier = 2, maxDelay = 2000))
    public ResponseEntity<byte[]> invoke(OutboundRequestSpec spec) {
        ResponseEntity<byte[]> resp = dispatch(spec);
        if (resp.getStatusCode().is5xxServerError()) {
            throw new HttpServerErrorException(resp.getStatusCode(), "上游 5xx");
        }
        if (resp.getStatusCode().value() == 429) {
            throw new org.springframework.web.client.HttpClientErrorException.TooManyRequests(
                    "上游 429 限流", resp.getStatusCode(), "429", resp.getHeaders(),
                    new byte[0], null);
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

    /** 判断异常是否属「结果不确定」（读超时/连接异常 → UNKNOWN 对账，M0-03 §3.1） */
    public static boolean isUnknownCause(Throwable cause) {
        return cause instanceof ResourceAccessException;
    }
}
