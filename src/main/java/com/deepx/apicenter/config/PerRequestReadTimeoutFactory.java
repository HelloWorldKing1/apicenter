package com.deepx.apicenter.config;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口级读超时（per-request）客户端工厂（D-PS-0 修复，2026-09-13）。
 *
 * <p>目标：让 {@code interface.timeout_ms}（schema 注释：出站 = 调供应商；入站 = 回调地址调用）
 * 真正按请求生效——修复前该列在管理面可配、文档可改，但 {@code UpstreamInvoker} 从不读取，
 * 实际读超时恒为全局硬编码的 3000ms（详见《前置接口编排设计方案.md》D-PS-0）。
 *
 * <p><b>为什么必须绕这一圈</b>（Spring 7 约束，均已用 {@code javap} 核实）：
 * <ul>
 *   <li>{@link JdkClientHttpRequestFactory#setReadTimeout} 是<b>实例级字段</b>，
 *       {@code createRequest(uri, method)} 只读它，没有任何 per-request 入口；</li>
 *   <li>{@code JdkClientHttpRequest} 及其构造器均为<b>包私有</b>，无法从业务包逐请求构造；
 *       {@code ClientHttpRequestInitializer} 只能改 header / body，改不了 timeout。</li>
 * </ul>
 * 因此实现为「委托 + 按超时值缓存工厂实例 + 栈式 ThreadLocal 作用域」：读超时由调用方在
 * {@link #withReadTimeout(Duration)} 作用域内声明，{@code createRequest} 按当前值取（无锁热路径）。
 *
 * <p><b>语义约定</b>：
 * <ul>
 *   <li>{@code timeout_ms <= 0} 或未声明作用域 → 回退 {@code defaultReadTimeout}（默认 3000ms，
 *       与 {@code interface.timeout_ms} 默认值一致）；<b>不支持「不限超时」</b>，避免慢上游挂死工作线程；</li>
 *   <li>连接超时保持全局（per-request 连接超时需要 per-request {@link HttpClient}，成本高收益低），
 *       见 {@code app.api-center.connect-timeout-ms}（{@code RestClientConfig}）；</li>
 *   <li>作用域为<b>栈式（嵌套安全）</b>：前置接口编排等嵌套调用内层覆盖外层、退出自动还原；
 *       {@code close()} 后清理 ThreadLocal，防 Tomcat / 补偿 worker 线程复用串账
 *       （与 {@code UpstreamInvoker.beginRetryBudget/endRetryBudget} 同一纪律）。</li>
 * </ul>
 *
 * <p>注：Spring 的读超时是「本地等待取消」（{@code TimeoutHandler} 内部 cancel CompletableFuture），
 * 供应商是否已收到 / 已处理仍不可判定——所以超时映射 UNKNOWN 对账的既有分类不变（M0-03 §2）。
 */
public class PerRequestReadTimeoutFactory implements ClientHttpRequestFactory {

    /** 兜底读超时（与 {@code interface.timeout_ms} 默认值 3000ms 对齐；配置非法 / 未声明作用域时使用） */
    private static final Duration FALLBACK_READ_TIMEOUT = Duration.ofMillis(3000);

    private final HttpClient httpClient;

    /** 兜底读超时（构造注入，来自 app.api-center.default-read-timeout-ms） */
    private final Duration defaultReadTimeout;

    /**
     * 按超时值缓存的委托工厂：{@code JdkClientHttpRequestFactory.readTimeout} 是实例字段，
     * 想按请求改超时只能「一值一实例」；值域极小（接口配置的 timeout_ms 集合），实例数有界。
     */
    private final Map<Duration, JdkClientHttpRequestFactory> perTimeout = new ConcurrentHashMap<>();

    /** 当前线程的读超时作用域栈（不声明则整个 ThreadLocal 不存在，热路径零开销） */
    private final ThreadLocal<Deque<Duration>> scope = new ThreadLocal<>();

    public PerRequestReadTimeoutFactory(HttpClient httpClient, Duration defaultReadTimeout) {
        this.httpClient = httpClient;
        this.defaultReadTimeout = (defaultReadTimeout == null || defaultReadTimeout.isZero()
                || defaultReadTimeout.isNegative())
                ? FALLBACK_READ_TIMEOUT : defaultReadTimeout;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        return factoryFor(currentReadTimeout()).createRequest(uri, httpMethod);
    }

    /**
     * 声明本次调用（含该调用内部全部短重试）的读超时；务必 try-with-resources 使用：
     * <pre>{@code try (var ignored = factory.withReadTimeout(Duration.ofMillis(spec.readTimeoutMs()))) { ... }}</pre>
     * 嵌套调用安全：内层 push 覆盖、退出 pop 还原（外层仍生效）。
     */
    public Scope withReadTimeout(Duration readTimeout) {
        Deque<Duration> stack = scopeStack();
        stack.push(normalize(readTimeout));
        return () -> {
            Deque<Duration> current = scope.get();
            if (current != null) {
                current.pop();
                if (current.isEmpty()) {
                    scope.remove(); // 防线程池复用残留（同 beginRetryBudget/endRetryBudget 纪律）
                }
            }
        };
    }

    /** 测试支撑：已缓存的委托工厂实例数（按超时值去重，断言无重复创建） */
    int cachedFactoryCount() {
        return perTimeout.size();
    }

    // ---------- 私有 ----------

    private Duration currentReadTimeout() {
        Deque<Duration> stack = scope.get();
        return (stack == null || stack.isEmpty()) ? defaultReadTimeout : stack.peek();
    }

    private JdkClientHttpRequestFactory factoryFor(Duration timeout) {
        return perTimeout.computeIfAbsent(timeout, t -> {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(t);
            return factory;
        });
    }

    /** 非法值（null / 0 / 负数）回退兜底：拒绝把 0ms 当成「立即超时」，也拒绝「不限超时」 */
    private Duration normalize(Duration readTimeout) {
        return (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative())
                ? defaultReadTimeout : readTimeout;
    }

    private Deque<Duration> scopeStack() {
        Deque<Duration> stack = scope.get();
        if (stack == null) {
            stack = new ArrayDeque<>(4);
            scope.set(stack);
        }
        return stack;
    }

    /**
     * 读超时作用域（AutoCloseable 且不收窄为受检异常的 {@code close()}，便于 try-with-resources）。
     */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
