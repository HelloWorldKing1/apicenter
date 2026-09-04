package com.deepx.apicenter.engine;

import com.deepx.apicenter.client.OutboundRequestSpec;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.resilience.retry.MethodRetryPredicate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException.TooManyRequests;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * 出站短重试 Invoker（M0-03 §1.4：@Retryable 必须放独立 Invoker 类——AOP 自调用不触发代理）。
 * Spring 7 内置 @Retryable（org.springframework.resilience.annotation，退避参数内联、无 @Backoff）；
 * 重试条件：HTTP 5xx、429、连接类 IO（ResourceAccessException）；4xx 非 429 不重试（返回由引擎分类）。
 * 重试耗尽后透传最后一次的原异常（Spring 7 语义），由 OutboundEngine 分类。
 * 熔断闸门（M4）置于本 Invoker 调用之前（技术架构 §4.5）。
 *
 * <p>实现说明（M0-03 C1 验证点结论）：RestClient 直调、uri(URI) 不经过 URI 模板展开——
 * 声明式客户端（@HttpExchange）对动态完整 URL 的模板变量会做路径编码导致 scheme 丢失。
 *
 * <p>接口级动态重试次数（M4 修正）：Spring 7 的 @Retryable 将 MethodRetrySpec 按
 * 「方法」缓存（RetryAnnotationBeanPostProcessor.retrySpecCache），maxRetriesString 的 SpEL
 * 仅在首次构建 spec 时求值一次——ThreadLocal + SpEL 方案对后续调用不生效（首调用的值被固定）。
 * 因此改用 @Retryable 的 predicate 扩展点（每次尝试后都会回调，与 includes 取 AND）承担真实预算：
 * 注解 maxRetries 固定为上限常量，predicate 按本次调用引擎设置的接口级预算判定是否继续。
 * 引擎须以 beginRetryBudget / endRetryBudget 包裹顶层调用（重置失败计数，防线程池复用串账）。
 */
@Component
public class UpstreamInvoker {

    /** 接口级重试预算传递通道（引擎 beginRetryBudget 设置；默认 4 次重试） */
    public static final ThreadLocal<Long> MAX_RETRIES = ThreadLocal.withInitial(() -> 4L);

    /** 本次调用已失败次数（predicate 判定是否继续重试；endRetryBudget 清零） */
    private static final ThreadLocal<Long> RETRY_FAILURES = ThreadLocal.withInitial(() -> 0L);

    /** 注解层重试次数上限（需 ≥ 接口级配置的最大合理值；真实预算由 predicate 收紧） */
    private static final long RETRY_CAP = 16L;

    private final RestClient restClient;

    public UpstreamInvoker(RestClient restClient) {
        this.restClient = restClient;
    }

    /** 引擎在每次顶层调用前设置接口级重试预算（同时清零失败计数，防线程池复用残留） */
    public static void beginRetryBudget(long maxRetries) {
        MAX_RETRIES.set(maxRetries);
        RETRY_FAILURES.set(0L);
    }

    /** 顶层调用结束（finally）清理 ThreadLocal（Tomcat 线程池复用防串账） */
    public static void endRetryBudget() {
        MAX_RETRIES.remove();
        RETRY_FAILURES.remove();
    }

    /**
     * 接口级重试预算判定：失败 1 次计 1，失败次数 ≤ 预算则继续重试
     * （maxRetries=0 → 首次失败即停，共 1 次调用；=2 → 共 3 次调用，与 Spring 语义一致）。
     * 与注解 includes（5xx/429/IO）取 AND，类型过滤仍由框架承担。
     */
    public static class InterfaceRetryBudgetPredicate implements MethodRetryPredicate {
        @Override
        public boolean shouldRetry(Method method, Throwable throwable) {
            long failures = RETRY_FAILURES.get() + 1;
            RETRY_FAILURES.set(failures);
            return failures <= MAX_RETRIES.get();
        }
    }

    @Retryable(includes = {HttpServerErrorException.class, TooManyRequests.class,
            org.springframework.web.client.ResourceAccessException.class},
            predicate = UpstreamInvoker.InterfaceRetryBudgetPredicate.class,
            maxRetries = RETRY_CAP,
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
        java.util.function.Consumer<org.springframework.http.HttpHeaders> headerSetter =
                h -> spec.headers().forEach(h::addAll);
        // GET / DELETE 不带 body（RestClient 对 GET 带 body 的兼容性保守处理）
        if ("GET".equals(method) || "DELETE".equals(method)) {
            return restClient.method(HttpMethod.valueOf(method))
                    .uri(URI.create(spec.url()))
                    .headers(headerSetter)
                    .retrieve()
                    .toEntity(byte[].class);
        }
        return restClient.method(HttpMethod.valueOf(method))
                .uri(URI.create(spec.url()))
                .headers(headerSetter)
                .body(spec.body())
                .retrieve()
                .toEntity(byte[].class);
    }
}
