package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.AdapterType;
import com.deepx.apicenter.engine.ChainPhase;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.CredentialRow;
import com.deepx.apicenter.repository.CredentialRepository;
import com.deepx.apicenter.service.CryptoService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HMAC 回调验签（M3 交付，D-M3-2；CALLBACK_AUTH 角色，INBOUND_AUTH 阶段）：
 * 校验供应商回调签名 = Hex(HMAC(secret, timestamp + "." + UTF-8(rawBody)))，时间戳头固定 X-Timestamp。
 * 密钥来自 app_credential（kind=CALLBACK，AES-256-GCM 解密）；ROTATING 新旧并存均验，任一命中即通过（M0-04 验签并存）。
 * 失败直接抛 BizException（40100 验签失败 / 40101 时间戳过期）→ HTTP 401，不落 inbound_delivery（M0-01 D7）。
 * replayProtection=true 时按 (appId, signature) 在容差窗口内内存去重（单实例，分布式去重 v1.1）。
 */
@Component("HmacCallbackVerifyAdapter")
public class HmacCallbackVerifyAdapter implements Adapter {

    private static final String DEFAULT_SIGNATURE_HEADER = "X-Partner-Signature";
    private static final int DEFAULT_TOLERANCE_SECONDS = 300;
    private static final int MAX_REPLAY_CACHE = 10_000;

    private final CredentialRepository credentialRepository;
    private final CryptoService cryptoService;

    /** 防重放窗口缓存：key = appId + ":" + signature → 过期毫秒时间戳（容差窗口 ×2） */
    private final Map<String, Long> replayCache = new ConcurrentHashMap<>();

    public HmacCallbackVerifyAdapter(CredentialRepository credentialRepository, CryptoService cryptoService) {
        this.credentialRepository = credentialRepository;
        this.cryptoService = cryptoService;
    }

    @Override
    public AdapterType type() {
        return AdapterType.AUTH;
    }

    @Override
    public AdapterContext process(AdapterContext ctx) {
        if (ctx.phase() != ChainPhase.INBOUND_AUTH) {
            return ctx;
        }
        verify(ctx);
        ctx.attrs().put("inboundAuthPassed", true);
        ctx.attrs().put("authAppId", ctx.app().appId());
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private void verify(AdapterContext ctx) {
        JsonNode params = (JsonNode) ctx.attrs().get("adapterParams");
        String algorithm = text(params, "signatureAlgorithm", "HMAC-SHA256");
        String signatureHeader = text(params, "signatureHeader", DEFAULT_SIGNATURE_HEADER);
        long tolerance = longParam(params, "timestampToleranceSeconds", DEFAULT_TOLERANCE_SECONDS);
        boolean replayProtection = boolParam(params, "replayProtection", false);

        Object headersObj = ctx.attrs().get("headers");
        Map<String, String> headers = headersObj instanceof Map<?, ?> m
                ? (Map<String, String>) m
                : Map.of();
        byte[] rawBody = (byte[]) ctx.attrs().get("rawBody");

        // 1. 时间戳校验（缺失/非法 → 40100；超出容差 → 40101）
        String timestamp = headers.get("X-Timestamp");
        if (timestamp == null || timestamp.isBlank()) {
            throw new BizException(40100, "验签失败：缺少时间戳头 X-Timestamp");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new BizException(40100, "验签失败：时间戳非法");
        }
        long nowSeconds = System.currentTimeMillis() / 1000;
        if (Math.abs(nowSeconds - ts) > tolerance) {
            throw new BizException(40101, "验签失败：时间戳超出容差（" + tolerance + " 秒）");
        }

        // 2. 签名校验（CALLBACK 凭证 ACTIVE + ROTATING 并存均验，任一命中即通过）
        String signature = headers.get(signatureHeader);
        if (signature == null || signature.isBlank()) {
            throw new BizException(40100, "验签失败：缺少签名头 " + signatureHeader);
        }
        String appId = ctx.app().appId();
        if (replayProtection && isReplayed(appId, signature, nowSeconds, tolerance)) {
            throw new BizException(40100, "验签失败：重复请求（防重放）");
        }
        List<CredentialRow> credentials = credentialRepository.findVerifiable(appId, "CALLBACK");
        boolean matched = false;
        for (CredentialRow credential : credentials) {
            String secret;
            try {
                secret = cryptoService.decrypt(credential.credential());
            } catch (Exception e) {
                continue; // 单条凭证解密失败不影响其他凭证
            }
            if (HmacSigner.verify(algorithm, secret, timestamp.trim(), rawBody, signature)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new BizException(40100, "验签失败：签名不匹配");
        }
        if (replayProtection) {
            markReplayed(appId, signature, nowSeconds, tolerance);
        }
    }

    private boolean isReplayed(String appId, String signature, long nowSeconds, long tolerance) {
        Long expireAt = replayCache.get(appId + ":" + signature);
        return expireAt != null && expireAt > nowSeconds * 1000;
    }

    private void markReplayed(String appId, String signature, long nowSeconds, long tolerance) {
        if (replayCache.size() > MAX_REPLAY_CACHE) {
            long now = nowSeconds * 1000;
            replayCache.entrySet().removeIf(e -> e.getValue() < now);
        }
        replayCache.put(appId + ":" + signature, nowSeconds * 1000 + tolerance * 2 * 1000L);
    }

    private String text(JsonNode params, String key, String def) {
        if (params == null || !params.has(key) || params.get(key).isNull() || params.get(key).asText().isBlank()) {
            return def;
        }
        return params.get(key).asText();
    }

    private long longParam(JsonNode params, String key, long def) {
        String v = text(params, key, "");
        if (v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private boolean boolParam(JsonNode params, String key, boolean def) {
        if (params == null || !params.has(key) || params.get(key).isNull()) {
            return def;
        }
        JsonNode node = params.get(key);
        return node.isBoolean() ? node.booleanValue() : Boolean.parseBoolean(node.asText());
    }
}
