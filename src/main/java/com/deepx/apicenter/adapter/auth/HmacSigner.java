package com.deepx.apicenter.adapter.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * HMAC 签名工具（D-M3-2：回调验签与出站 HMAC 签名共用；出站 HmacAuthAdapter 属多鉴权批，M3 仅回调方向）。
 * 签名字符串（首期固定约定）：timestamp + "." + UTF-8(rawBody)，整体按 UTF-8 编码后 HMAC，hex 小写。
 */
public final class HmacSigner {

    private HmacSigner() {
    }

    /** 计算签名（供验签比对与「模拟回调」测试端点自签名） */
    public static String sign(String algorithm, String secret, String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(jcaName(algorithm));
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), jcaName(algorithm)));
            String signString = timestamp + "." + new String(rawBody == null ? new byte[0] : rawBody, StandardCharsets.UTF_8);
            return hex(mac.doFinal(signString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败：" + e.getMessage(), e);
        }
    }

    /** 常量时间比对（防时序侧信道；signature 为空恒 false） */
    public static boolean verify(String algorithm, String secret, String timestamp, byte[] rawBody, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(algorithm, secret, timestamp, rawBody).getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** 目录参数名（HMAC-SHA256）→ JCA 标准名（HmacSHA256） */
    private static String jcaName(String algorithm) {
        return switch (algorithm) {
            case "HMAC-SHA256" -> "HmacSHA256";
            case "HMAC-SHA1" -> "HmacSHA1";
            case "HMAC-SHA512" -> "HmacSHA512";
            default -> algorithm; // 其余按 JCA 标准名直接透传
        };
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
