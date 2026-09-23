package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.AdapterType;
import com.deepx.apicenter.engine.ChainPhase;
import com.deepx.apicenter.exception.BizException;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 入站鉴权适配器**公共基类**（2026-09-23，B2）：统一「阶段分流 + 上下文取值 + 常量时间比较」，
 * 子类只实现 {@link #verify(AdapterContext)} 的具体校验。
 *
 * <p>遵守 D-CA-6：**适配器不查库**，凭证来自 {@code attrs("inboundCredentials")}（注入方提供）。
 * 非 `INBOUND_AUTH` 阶段一律直通（与既有 M3 适配器同口径）。
 */
abstract class AbstractClientVerifyAdapter implements InboundAuthAdapter {

    static final String DEFAULT_ID_HEADER = "X-Client-Id";

    @Override
    public AdapterType type() {
        return AdapterType.AUTH;
    }

    @Override
    public AdapterContext process(AdapterContext ctx) {
        if (ctx.phase() != ChainPhase.INBOUND_AUTH) {
            return ctx;   // 非入站阶段直通（与既有适配器一致）
        }
        verify(ctx);
        ctx.attrs().put("inboundAuthPassed", true);
        ctx.attrs().put("inboundAuthMethod", method());
        return ctx;
    }

    /** 具体校验；失败抛 {@code BizException(40100/40101/40103)} */
    protected abstract void verify(AdapterContext ctx);

    // ---------- 上下文取值（约定见 InboundAuthAdapter 的类注释） ----------

    @SuppressWarnings("unchecked")
    protected Map<String, String> headers(AdapterContext ctx) {
        Object o = ctx.attrs().get("headers");
        return o instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    protected List<String> credentials(AdapterContext ctx) {
        Object o = ctx.attrs().get("inboundCredentials");
        return o instanceof List<?> l ? (List<String>) l : List.of();
    }

    protected byte[] rawBody(AdapterContext ctx) {
        return ctx.attrs().get("rawBody") instanceof byte[] b ? b : new byte[0];
    }

    protected JsonNode params(AdapterContext ctx) {
        return ctx.attrs().get("adapterParams") instanceof JsonNode n ? n : null;
    }

    protected String text(JsonNode params, String key, String def) {
        if (params == null || !params.has(key) || params.get(key).isNull() || params.get(key).asText().isBlank()) {
            return def;
        }
        return params.get(key).asText().trim();
    }

    /**
     * 取值（**空串有语义**，2026-09-23）：仅当键**缺失/null** 时用默认值；显式空串按空串返回。
     * 用于 `prefix` 这类「置空 = 裸 token」的字段 —— 用 {@link #text} 会把显式空串当成"未配置"而回退默认
     * （出站 `BearerTokenAuthAdapter` 2026-09-18 踩过同类坑；XML `namespace.prefix` 也是这个语义）。
     */
    protected String textOrEmpty(JsonNode params, String key, String def) {
        if (params == null || !params.has(key) || params.get(key).isNull()) {
            return def;
        }
        return params.get(key).asText().trim();
    }

    protected long longParam(JsonNode params, String key, long def) {
        String v = text(params, key, "");
        if (v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    protected boolean boolParam(JsonNode params, String key, boolean def) {
        if (params == null || !params.has(key) || params.get(key).isNull()) {
            return def;
        }
        JsonNode node = params.get(key);
        return node.isBoolean() ? node.booleanValue() : Boolean.parseBoolean(node.asText());
    }

    /** 常量时间字符串比较（D-CA-13：避免按字节早退泄露信息） */
    protected static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 逐条比对注入的凭证（ACTIVE + ROTATING 并存，任一命中即通过） */
    protected static boolean anyCredentialMatches(List<String> credentials, String presented) {
        boolean matched = false;
        for (String secret : credentials) {
            // 不用 || 短路：保持「每条都参与比较」的常量时间口径
            matched |= constantTimeEquals(secret, presented);
        }
        return matched;
    }

    /** 主体标识头的值（缺失返回空串） */
    protected String clientIdHeader(AdapterContext ctx, String headerName) {
        String v = headers(ctx).get(headerName);
        return v == null ? "" : v.trim();
    }

    protected BizException fail(int code, String msg) {
        return new BizException(code, msg);
    }
}
