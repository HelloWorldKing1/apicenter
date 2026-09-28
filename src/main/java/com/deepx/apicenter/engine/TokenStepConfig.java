package com.deepx.apicenter.engine;

import com.deepx.apicenter.exception.BizException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * **令牌步骤参数**（`interface_step.token_config`，2026-09-24 方案 B）。
 *
 * <p>用途：把「先用 AK/SK 换 token、再调业务接口」里的**换 token 那一步**变成可缓存的步骤 ——
 * 命中缓存就**跳过 HTTP**（这是硬要求：云厂商的换发接口普遍有限频，每次业务请求都去换会被限流）。
 *
 * <h3>JSON 形态</h3>
 * <pre>
 * {
 *   "tokenPath": "data.access_token",     // 必填：响应里 token 的点路径（模型语义）
 *   "ttlPath":   "data.expires_in",       // 可选：响应里"有效期"的位置；不填则用 ttlFallbackSeconds
 *   "ttlMode":   "SECONDS",               // SECONDS(默认/剩余秒) | EPOCH_MS | EPOCH_S | ISO8601(到期时刻)
 *   "ttlFallbackSeconds": 300,            // 兜底有效期（0 = 不缓存，每次换发；调试用）
 *   "refreshAheadSeconds": 60             // 提前刷新（默认 60；必须 < 兜底有效期）
 * }
 * </pre>
 *
 * <p><b>为什么四种 ttlMode</b>：腾讯 STS 返回 `ExpiredTime`（ISO8601）、阿里 STS 返回 `Expiration`（ISO8601），
 * 而 OAuth2 普遍返回 `expires_in`（剩余秒）—— 两种语义都存在，必须都支持，否则"到期"只能靠猜。
 */
public record TokenStepConfig(String tokenPath, String ttlPath, String ttlMode,
                              int ttlFallbackSeconds, int refreshAheadSeconds) {

    /** 不缓存（每次都换发）——调试用；生产中不缓存会被限频 */
    public static final int MAX_TTL_SECONDS = 24 * 3600;

    private static final int DEFAULT_REFRESH_AHEAD_SECONDS = 60;

    /** 解析 + **保存期校验**（非法一律 40001，不静默取默认值） */
    public static TokenStepConfig parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            throw BizException.fieldInvalid("令牌步骤必须配置 tokenConfig（至少含 tokenPath）");
        }
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            throw BizException.fieldInvalid("令牌步骤参数 JSON 非法：" + e.getMessage());
        }
        String tokenPath = text(node, "tokenPath");
        if (tokenPath == null || tokenPath.isBlank()) {
            throw BizException.fieldInvalid("令牌步骤参数必填 tokenPath（响应里 token 的点路径，如 data.access_token）");
        }
        String ttlPath = text(node, "ttlPath");
        String mode = text(node, "ttlMode") == null || text(node, "ttlMode").isBlank()
                ? "SECONDS" : text(node, "ttlMode").trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("SECONDS", "EPOCH_MS", "EPOCH_S", "ISO8601").contains(mode)) {
            throw BizException.fieldInvalid("ttlMode 仅支持 SECONDS / EPOCH_MS / EPOCH_S / ISO8601：" + mode);
        }
        int fallback = intOr(node, "ttlFallbackSeconds", 300);
        if (fallback < 0 || fallback > MAX_TTL_SECONDS) {
            throw BizException.fieldInvalid("ttlFallbackSeconds 取值 0~" + MAX_TTL_SECONDS + "（0 = 不缓存）：" + fallback);
        }
        int ahead = intOr(node, "refreshAheadSeconds", DEFAULT_REFRESH_AHEAD_SECONDS);
        if (ahead < 0 || ahead > 3600) {
            throw BizException.fieldInvalid("refreshAheadSeconds 取值 0~3600：" + ahead);
        }
        if (fallback > 0 && ahead >= fallback) {
            throw BizException.fieldInvalid(
                    "refreshAheadSeconds(" + ahead + ") 必须小于 ttlFallbackSeconds(" + fallback + ")，否则每次都会刷新");
        }
        return new TokenStepConfig(tokenPath.trim(), ttlPath == null || ttlPath.isBlank() ? null : ttlPath.trim(),
                mode, fallback, ahead);
    }

    /** 缓存可用性判据：是否已进入"提前刷新窗口" */
    public long refreshAheadMillis() {
        return refreshAheadSeconds * 1000L;
    }

    /** 是否禁用缓存（ttlFallbackSeconds=0 且没有 ttlPath 时无有效期可言 ⇒ 不缓存） */
    public boolean cacheDisabled() {
        return ttlFallbackSeconds == 0 && ttlPath == null;
    }

    /**
     * 把响应里的"有效期"值换算成**从现在起还有多少毫秒**；缺失/不可解析/非正数 ⇒ 用兜底值并返回 {@code -1} 表示"用了兜底"。
     *
     * @param rawTtl 响应中 `ttlPath` 指向的值（可为 null）
     * @return 剩余毫秒（已 clamp 到 [0, 24h]）
     */
    public long ttlMillisFrom(Object rawTtl) {
        long now = System.currentTimeMillis();
        Long parsed = parseTtl(rawTtl, now);
        if (parsed == null) {
            return cacheDisabled() ? 0 : ttlFallbackSeconds * 1000L;
        }
        return Math.max(0, Math.min(parsed, MAX_TTL_SECONDS * 1000L));
    }

    private Long parseTtl(Object rawTtl, long now) {
        if (rawTtl == null) {
            return null;
        }
        String value = String.valueOf(rawTtl).trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return switch (ttlMode) {
                case "SECONDS" -> (long) (Double.parseDouble(value) * 1000);
                case "EPOCH_MS" -> Long.parseLong(value) - now;
                case "EPOCH_S" -> Long.parseLong(value) * 1000 - now;
                case "ISO8601" -> Duration.between(Instant.now(), Instant.parse(value)).toMillis();
                default -> null;
            };
        } catch (Exception e) {
            return null;   // 解析失败 ⇒ 兜底（不抛：这是运行时数据，不该让整次调用失败）
        }
    }

    private static String text(JsonNode node, String key) {
        return node.hasNonNull(key) ? node.get(key).asText() : null;
    }

    private static int intOr(JsonNode node, String key, int def) {
        if (!node.hasNonNull(key)) {
            return def;
        }
        try {
            return Integer.parseInt(node.get(key).asText().trim());
        } catch (NumberFormatException e) {
            throw BizException.fieldInvalid(key + " 必须是整数：" + node.get(key).asText());
        }
    }
}
