package com.deepx.apicenter.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 签名 / 验签工具 —— HMAC-SHA256（设计文档 §7.3、application.yaml signature-tolerance-seconds=300）。
 *
 * <p>用于：
 * <ul>
 *   <li>Flow A：校验 ERP 调用组件的 {@code X-Signature}</li>
 *   <li>Flow B：校验第三方回调组件的 {@code X-Partner-Signature}</li>
 * </ul>
 * canonicalString 的拼接规则由各渠道约定（示例：appId + timestamp + bizId）。
 */
@Service
public class SignatureService {

    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * 计算签名：HMAC-SHA256(canonicalString, secret) 的十六进制小写串。
     */
    public String sign(String canonicalString, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(canonicalString.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // 算法/密钥非法属于编程错误，直接抛出
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    /**
     * 验签：时间戳在容差窗口内且签名恒定时间相等才算通过。
     *
     * @param signature        请求方携带的签名
     * @param canonicalString  待签名的规范化串
     * @param secret           渠道密钥
     * @param timestamp        请求方时间戳（epoch 秒）
     * @param toleranceSeconds 时间戳容差（秒）
     */
    public boolean verify(String signature, String canonicalString, String secret,
                          String timestamp, int toleranceSeconds) {
        // 1) 时间戳容差：防重放
        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - ts) > toleranceSeconds) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        // 2) 签名比对（恒定时间比较，防时序侧信道）
        return constantTimeEquals(sign(canonicalString, secret), signature);
    }

    /**
     * 恒定时间字符串比较：长度相等且逐位异或无差异才通过。
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
