package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.InboundCredential;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **调用方 HMAC 验签**（2026-09-23，B2；设计方案 §5.1 / §5.3 / §6.4）。
 *
 * <p>三件事：① 时间戳容差（默认 300s）② 签名
 * `Hex(HMAC(algo, secret, timestamp + "." + UTF-8(rawBody)))` ③ 防重放（可选）。
 *
 * <p>**签名串与回调验签完全同口径**（复用 {@link HmacSigner}，含 `MessageDigest.isEqual` 常量时间比较）
 * —— 调用方与供应商可以用**同一个签名工具 / 同一份联调脚本**，这是刻意的运维减法（§5.1）。
 *
 * <p>与 `HmacCallbackVerifyAdapter` 的唯一差别：**本类不查库**（凭证由注入方提供，D-CA-6），
 * 因此同一实例既能服务调用方鉴权，也能服务回调验签（§9.3）。
 */
@Component("ClientHmacVerifyAdapter")
public class ClientHmacVerifyAdapter extends AbstractClientVerifyAdapter {

    private static final String DEFAULT_SIGNATURE_HEADER = "X-Signature";
    private static final String DEFAULT_TIMESTAMP_HEADER = "X-Timestamp";
    private static final int DEFAULT_TOLERANCE_SECONDS = 300;
    private static final int MAX_REPLAY_CACHE = 10_000;

    /** 防重放窗口缓存：key = 主体标识 + ":" + signature → 过期毫秒时间戳（容差窗口 ×2） */
    private final Map<String, Long> replayCache = new ConcurrentHashMap<>();

    @Override
    public String method() {
        return "HMAC-SHA256";   // 默认算法；params.signatureAlgorithm 可改（HMAC-SHA1 / HMAC-SHA512）
    }

    @Override
    public String credentialKind() {
        return "HMAC_SECRET";
    }

    @Override
    protected void verify(AdapterContext ctx) {
        var params = params(ctx);
        String idHeader = text(params, "idHeaderName", DEFAULT_ID_HEADER);
        String algorithm = text(params, "signatureAlgorithm", "HMAC-SHA256");
        String signatureHeader = text(params, "signatureHeader", DEFAULT_SIGNATURE_HEADER);
        String timestampHeader = text(params, "timestampHeader", DEFAULT_TIMESTAMP_HEADER);
        long tolerance = longParam(params, "timestampToleranceSeconds", DEFAULT_TOLERANCE_SECONDS);
        boolean replayProtection = boolParam(params, "replayProtection", false);

        // ① 时间戳（缺失/非法 → 40100；超容差 → 40101）
        String timestamp = clientIdHeader(ctx, timestampHeader);
        if (timestamp.isEmpty()) {
            throw fail(40100, "验签失败：缺少时间戳头 " + timestampHeader);
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw fail(40100, "验签失败：时间戳非法");
        }
        long nowSeconds = System.currentTimeMillis() / 1000;
        if (Math.abs(nowSeconds - ts) > tolerance) {
            throw fail(40101, "验签失败：时间戳超出容差（" + tolerance + " 秒）");
        }

        // ② 签名（ACTIVE + ROTATING 并存均验，任一命中即通过）
        String signature = clientIdHeader(ctx, signatureHeader);
        if (signature.isEmpty()) {
            throw fail(40100, "验签失败：缺少签名头 " + signatureHeader);
        }
        String principal = clientIdHeader(ctx, idHeader);
        if (replayProtection && isReplayed(principal, signature, nowSeconds)) {
            throw fail(40100, "验签失败：重复请求（防重放）");
        }
        // v1.2：候选凭证带 id/备注/指纹 —— 命中时回写上下文，供审计归因（D-CA-20）
        List<InboundCredential> candidates = candidates(ctx);
        if (candidates.isEmpty()) {
            throw fail(40100, "验签失败：无可用凭证");
        }
        boolean matched = false;
        long matchedId = InboundCredential.NO_ID;
        String matchedLabel = null;
        String matchedFingerprint = null;
        for (InboundCredential c : candidates) {
            boolean hit = HmacSigner.verify(algorithm, c.plaintext(), timestamp, rawBody(ctx), signature);
            if (hit && !matched) {
                matchedId = c.id();
                matchedLabel = c.label();
                matchedFingerprint = c.fingerprint();
            }
            matched |= hit;   // 不早退：保持「每条都参与验签」的常量时间口径
        }
        if (!matched) {
            throw fail(40100, "验签失败：签名不匹配");
        }
        ctx.attrs().put("matchedCredentialId", matchedId);
        if (matchedLabel != null) {
            ctx.attrs().put("matchedCredentialLabel", matchedLabel);
        }
        if (matchedFingerprint != null) {
            ctx.attrs().put("matchedCredentialFingerprint", matchedFingerprint);
        }
        if (replayProtection) {
            markReplayed(principal, signature, nowSeconds, tolerance);
        }
    }

    private boolean isReplayed(String principal, String signature, long nowSeconds) {
        Long expireAt = replayCache.get(principal + ":" + signature);
        return expireAt != null && expireAt > nowSeconds * 1000;
    }

    private void markReplayed(String principal, String signature, long nowSeconds, long tolerance) {
        if (replayCache.size() > MAX_REPLAY_CACHE) {
            long now = nowSeconds * 1000;
            replayCache.entrySet().removeIf(e -> e.getValue() < now);   // 有界 + 惰性清理
        }
        replayCache.put(principal + ":" + signature, nowSeconds * 1000 + tolerance * 2 * 1000L);
    }
}
