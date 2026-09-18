package com.deepx.apicenter.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 接口级读超时（per-request）行为单测（D-PS-0 修复，2026-09-13）。
 *
 * <p>纯单测：JDK 内置 {@link HttpServer} 提供「快端点 / 慢端点（固定延迟 1200ms）」，
 * 不依赖数据库、Spring 上下文与 WireMock——读超时是网络行为，用本地 server 断言最直接：
 * <ul>
 *   <li>慢端点 + 小超时 → 在读超时时刻失败（而不是上游延迟时刻）；</li>
 *   <li>慢端点 + 大超时 → 正常返回（证明不是「一律失败」的假绿）；</li>
 *   <li>未声明作用域 / 非法值 → 回退兜底读超时；</li>
 *   <li>嵌套作用域：内层覆盖外层，退出还原（前置接口编排的嵌套调用依赖）；</li>
 *   <li>按超时值缓存工厂实例（无重复创建）。</li>
 * </ul>
 */
class PerRequestReadTimeoutFactoryTest {

    /** 慢端点延迟（ms）：须明显大于小超时、明显小于大超时与兜底超时 */
    private static final int SLOW_DELAY_MS = 1200;

    private static HttpServer server;
    private static int port;

    private PerRequestReadTimeoutFactory factory;
    private RestClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 慢端点可能并发阻塞：给多线程执行器，避免慢请求串行化快端点
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/fast", exchange -> respond(exchange, "ok"));
        server.createContext("/slow", exchange -> {
            sleep(SLOW_DELAY_MS);
            respond(exchange, "ok");
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() {
        // 兜底读超时 3000ms（> 慢端点 1200ms）：便于「未声明作用域」用例断言成功
        factory = new PerRequestReadTimeoutFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                Duration.ofMillis(3000));
        client = RestClient.builder().requestFactory(factory).build();
    }

    // ---------- 核心：接口级读超时真的生效 ----------

    @Test
    void 小超时_慢上游在读超时时刻中断() {
        // 判定不用墙钟：超时 200ms < 上游 1200ms → 必然抛异常；
        // 若作用域失效（回退兜底 3000ms > 1200ms）→ 上游正常返回 → 本用例变红。
        assertThatThrownBy(() -> {
            try (PerRequestReadTimeoutFactory.Scope ignored = factory.withReadTimeout(Duration.ofMillis(200))) {
                get("/slow");
            }
        })
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(java.net.http.HttpTimeoutException.class); // 是「读超时」而非连接错误
    }

    @Test
    void 大超时_慢上游正常返回() {
        try (PerRequestReadTimeoutFactory.Scope ignored = factory.withReadTimeout(Duration.ofMillis(5000))) {
            assertThat(get("/slow")).isEqualTo("ok");
        }
    }

    @Test
    void 未声明作用域_回退兜底读超时() {
        // 兜底 3000ms > 1200ms → 成功（若兜底被误设为 0/极小，本用例会失败）
        assertThat(get("/slow")).isEqualTo("ok");
    }

    @Test
    void 快上游_任何超时都成功() {
        try (PerRequestReadTimeoutFactory.Scope ignored = factory.withReadTimeout(Duration.ofMillis(200))) {
            assertThat(get("/fast")).isEqualTo("ok");
        }
    }

    // ---------- 作用域语义 ----------

    @Test
    void 非法超时值_回退兜底而非立即超时() {
        for (Duration illegal : new Duration[]{Duration.ZERO, Duration.ofMillis(-1)}) {
            try (PerRequestReadTimeoutFactory.Scope ignored = factory.withReadTimeout(illegal)) {
                assertThat(get("/slow")).as("非法值 %s 应回退兜底 3000ms", illegal).isEqualTo("ok");
            }
        }
    }

    @Test
    void 嵌套作用域_内层覆盖外层_退出后外层还原() {
        // 外层小超时：慢端点必抛异常
        try (PerRequestReadTimeoutFactory.Scope outer = factory.withReadTimeout(Duration.ofMillis(200))) {
            assertThatThrownBy(() -> get("/slow")).isInstanceOf(ResourceAccessException.class);
            // 内层大超时：覆盖外层 → 成功
            try (PerRequestReadTimeoutFactory.Scope inner = factory.withReadTimeout(Duration.ofMillis(5000))) {
                assertThat(get("/slow")).isEqualTo("ok");
            }
            // 内层退出 → 外层恢复：再次失败（证明「还原」而非「被内层永久改写」）
            assertThatThrownBy(() -> get("/slow")).isInstanceOf(ResourceAccessException.class);
        }
        // 全部退出 → 兜底生效
        assertThat(get("/slow")).isEqualTo("ok");
    }

    @Test
    void 作用域关闭后_线程无残留超时() {
        try (PerRequestReadTimeoutFactory.Scope ignored = factory.withReadTimeout(Duration.ofMillis(200))) {
            assertThatThrownBy(() -> get("/slow")).isInstanceOf(ResourceAccessException.class);
        }
        // 同一线程再次调用：不受上一次作用域影响（ThreadLocal 已清理）
        assertThat(get("/slow")).isEqualTo("ok");
    }

    // ---------- 实现细节：工厂实例缓存 ----------

    @Test
    void 按超时值缓存委托工厂_同值复用_不同值新建() {
        assertThat(factory.cachedFactoryCount()).isZero();
        try (PerRequestReadTimeoutFactory.Scope ignored = factory.withReadTimeout(Duration.ofMillis(200))) {
            get("/fast");
        }
        assertThat(factory.cachedFactoryCount()).isEqualTo(1);
        // 同值再次使用 → 复用（不新建）
        try (PerRequestReadTimeoutFactory.Scope ignored = factory.withReadTimeout(Duration.ofMillis(200))) {
            get("/fast");
        }
        assertThat(factory.cachedFactoryCount()).isEqualTo(1);
        // 新值 → 新建
        try (PerRequestReadTimeoutFactory.Scope ignored = factory.withReadTimeout(Duration.ofMillis(3000))) {
            get("/fast");
        }
        assertThat(factory.cachedFactoryCount()).isEqualTo(2);
        // 未声明作用域 → 兜底值（3000ms）已缓存 → 不新增
        get("/fast");
        assertThat(factory.cachedFactoryCount()).isEqualTo(2);
    }

    // ---------- 辅助 ----------

    private String get(String path) {
        return client.get().uri("http://127.0.0.1:" + port + path).retrieve().body(String.class);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
