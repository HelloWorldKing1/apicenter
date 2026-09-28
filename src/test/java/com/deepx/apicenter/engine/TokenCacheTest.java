package com.deepx.apicenter.engine;

import com.deepx.apicenter.config.ConfigChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌缓存单测（2026-09-24 方案 B）：命中/提前刷新/失效/上限/过期清理。
 *
 * <p>它存在的意义：云厂商换发接口普遍限频 ⇒ **缓存是"AK/SK 换 Token"这条路的硬要求**，
 * 而"提前刷新窗口"决定临界过期时业务请求会不会突然 401。
 */
class TokenCacheTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TokenCache cache = new TokenCache(registry);

    @Test
    void 命中与提前刷新窗口() {
        String key = TokenCache.key(9L, "auth");
        cache.put(key, "TOK-1", 300_000);
        // 未进入提前刷新窗口 ⇒ 命中
        assertThat(cache.get(key, 60_000)).isNotNull();
        assertThat(cache.get(key, 60_000).token()).isEqualTo("TOK-1");
        // 剩余不足提前量 ⇒ 视为需要刷新（返回 null，调用方去换发）
        cache.put(TokenCache.key(9L, "soon"), "TOK-2", 30_000);
        assertThat(cache.get(TokenCache.key(9L, "soon"), 60_000)).isNull();
    }

    @Test
    void 键含stepCode_同一接口不同步骤互不干扰() {
        cache.put(TokenCache.key(9L, "a"), "TOK-A", 300_000);
        cache.put(TokenCache.key(9L, "b"), "TOK-B", 300_000);
        assertThat(cache.get(TokenCache.key(9L, "a"), 0).token()).isEqualTo("TOK-A");
        assertThat(cache.get(TokenCache.key(9L, "b"), 0).token()).isEqualTo("TOK-B");
    }

    @Test
    void 接口配置变更_按interfaceId前缀整体失效() {
        cache.put(TokenCache.key(9L, "a"), "TOK-9A", 300_000);
        cache.put(TokenCache.key(10L, "a"), "TOK-10A", 300_000);

        cache.onConfigChanged(ConfigChangedEvent.interfaceChanged(9L));

        assertThat(cache.get(TokenCache.key(9L, "a"), 0)).isNull();
        assertThat(cache.get(TokenCache.key(10L, "a"), 0)).isNotNull();   // 别的接口不受影响
    }

    @Test
    void 过期清理与非法值不入缓存() {
        cache.put(TokenCache.key(1L, "x"), "TOK", 1L);          // 1ms ⇒ 立即过期
        try {
            Thread.sleep(10);                                   // 等过时钟粒度（毫秒级）
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        cache.purgeExpired();
        assertThat(cache.get(TokenCache.key(1L, "x"), 0)).isNull();

        cache.put(TokenCache.key(1L, "y"), "", 300_000);        // 空 token 不入缓存
        cache.put(TokenCache.key(1L, "z"), "TOK", 0);           // 0 TTL = 不缓存
        assertThat(cache.size()).isZero();
    }

    @Test
    void 指标计数_命中与换发成功失败都可观测() {
        String key = TokenCache.key(3L, "auth");
        cache.put(key, "TOK", 300_000);
        cache.get(key, 0);
        cache.countRefresh(key, true);
        cache.countRefresh(key, false);

        assertThat(registry.get("apicenter.token.cache").tag("result", "store").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("apicenter.token.cache").tag("result", "hit").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("apicenter.token.cache").tag("result", "refresh_ok").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("apicenter.token.cache").tag("result", "refresh_fail").counter().count()).isEqualTo(1.0);
    }
}
