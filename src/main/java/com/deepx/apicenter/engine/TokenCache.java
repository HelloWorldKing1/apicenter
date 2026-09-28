package com.deepx.apicenter.engine;

import com.deepx.apicenter.config.ConfigChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **令牌缓存**（2026-09-24，方案 B 的核心之一）：让「先换 token 再调业务」的**换发环节**不必每个请求都做。
 *
 * <p>为什么必须有它：云厂商换发接口普遍有限频（腾讯云文档原话：「建议在有效期内重复使用，
 * 避免请求该接口频率达到上限被限频」）。没有缓存的话，「AK/SK 换 Token」这条路在真实流量下**不可用**。
 *
 * <h3>口径（与熔断 / 限流一致）</h3>
 * <ul>
 *   <li><b>单实例内存</b>：多实例各自持有 N 份 token（换发次数 = 实例数，而不是请求数）；</li>
 *   <li><b>提前刷新</b>：进入 `refreshAheadSeconds` 窗口即视为"需刷新"，避免临界过期导致业务请求 401；</li>
 *   <li><b>键</b> = `interfaceId#stepCode`（**不是 stepId**：保存配置会整表重建步骤、id 会变）；
 *       因此**接口配置变更必须失效**（见 {@link #onConfigChanged}）。</li>
 * </ul>
 *
 * <h3>已知限制（如实记录）</h3>
 * 冷启动瞬间的**并发穿透**：同一实例上 N 个并发请求同时发现"无缓存"时，会各自换发一次
 * （各自只发一次、不是每请求一次；TTL 内不再重复）。真正的 single-flight 需要把「HTTP 换发流程」
 * 重构为可锁定的 supplier（收益有限、风险不小），列为 P2；当前用指标
 * `apicenter.token.refresh{step,result}` 观测换发频率是否异常。
 */
@Component
public class TokenCache {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TokenCache.class);

    /** 缓存条目上限（防极端配置把内存撑爆；超出后不再新增，仅记 warn） */
    private static final int MAX_ENTRIES = 1000;

    /** 缓存项：token 明文**只在内存**，永不落库、不入日志（日志另有 SensitiveDataMasker 兜底） */
    public record Cached(String token, long expiresAtMillis) {

        /** 是否仍可用（未进入"提前刷新窗口"） */
        public boolean usable(long refreshAheadMillis) {
            return System.currentTimeMillis() < expiresAtMillis - refreshAheadMillis;
        }

        public long remainingSeconds() {
            return Math.max(0, (expiresAtMillis - System.currentTimeMillis()) / 1000);
        }
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public TokenCache(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 键：`interfaceId#stepCode`（接口配置变更时按 interfaceId 前缀整体失效） */
    public static String key(long interfaceId, String stepCode) {
        return interfaceId + "#" + stepCode;
    }

    /**
     * 取可用令牌（未进入提前刷新窗口才返回；否则返回 null 表示"需要换发"）。
     */
    public Cached get(String key, long refreshAheadMillis) {
        Cached cached = cache.get(key);
        if (cached == null) {
            return null;
        }
        if (cached.usable(refreshAheadMillis)) {
            count("hit", stepOf(key));
            return cached;
        }
        count("stale", stepOf(key));
        return null;
    }

    /** 存入换发结果（ttlMillis ≤ 0 ⇒ 不缓存，仅计数） */
    public void put(String key, String token, long ttlMillis) {
        if (token == null || token.isBlank() || ttlMillis <= 0) {
            count("uncached", stepOf(key));
            return;
        }
        if (cache.size() >= MAX_ENTRIES && !cache.containsKey(key)) {
            log.warn("令牌缓存已达上限 {}，本条不入缓存（key={}）；请检查是否有异常多的令牌步骤", MAX_ENTRIES, key);
            count("overflow", stepOf(key));
            return;
        }
        cache.put(key, new Cached(token, System.currentTimeMillis() + ttlMillis));
        count("store", stepOf(key));
    }

    /** 记录一次"实际发起了换发"（供指标观测换发频率） */
    public void countRefresh(String key, boolean success) {
        count(success ? "refresh_ok" : "refresh_fail", stepOf(key));
    }

    /** 失效某接口的全部令牌（接口配置变更：步骤可能被改/删/换目标） */
    public void evict(long interfaceId) {
        String prefix = interfaceId + "#";
        int before = cache.size();
        cache.keySet().removeIf(k -> k.startsWith(prefix));
        if (before != cache.size()) {
            log.info("令牌缓存失效：interface={}，清理 {} 条", interfaceId, before - cache.size());
        }
    }

    /** 清空过期条目（写入路径顺带清理，避免长期驻留） */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> e.getValue().expiresAtMillis() < now);
    }

    public int size() {
        return cache.size();
    }

    /** 测试支撑 */
    public void clear() {
        cache.clear();
    }

    /**
     * 接口配置变更 ⇒ 失效该接口的令牌（键含 stepCode，配置可能已改）。
     * 与 `ChainEngine` 同款时序（AFTER_COMMIT + 无事务时立即执行）。
     */
    @EventListener
    public void onConfigChanged(ConfigChangedEvent evt) {
        if (evt.scope() == ConfigChangedEvent.Scope.INTERFACE && evt.interfaceId() != null) {
            evict(evt.interfaceId());
        }
    }

    // ---------- 私有 ----------

    private static String stepOf(String key) {
        int idx = key.indexOf('#');
        return idx < 0 ? "-" : key.substring(idx + 1);
    }

    private void count(String result, String step) {
        // 标签基数有界：result 是固定枚举、step 是步骤名（接口配置，数量有限）
        meterRegistry.counter("apicenter.token.cache", "result", result, "step", step).increment();
    }
}
