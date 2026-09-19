package com.deepx.apicenter.service;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 口令哈希（管理面账号，2026-09-18）：PBKDF2-HMAC-SHA256，JDK 自带实现（零新增依赖）。
 *
 * <p>存储格式：`pbkdf2$<iterations>$<saltBase64>$<hashBase64>`（自带参数，便于日后升级迭代次数而不破坏旧值）。
 * 每用户随机盐 16 字节；校验用 {@link MessageDigest#isEqual} 常量时间比较（防时序侧信道）。
 *
 * <p>与 {@link CryptoService}（可逆 AES）刻意分开：凭证必须可逆（签名要重算明文），口令必须**不可逆**。
 */
@Component
public class PasswordHasher {

    private static final String ALGO = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    /** 迭代次数：兼顾安全与登录延迟（本机约 50-120ms） */
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$" + base64(salt) + "$" + base64(hash);
    }

    /** 校验口令；存储值格式非法一律返回 false（不抛异常，避免泄漏内部格式细节） */
    public boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] pbkdf2(String password, byte[] salt, int iterations) {
        return pbkdf2(password, salt, iterations, KEY_BITS);
    }

    private byte[] pbkdf2(String password, byte[] salt, int iterations, int keyBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBits);
            try {
                return SecretKeyFactory.getInstance(ALGO).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("口令哈希失败：" + e.getMessage(), e);
        }
    }

    private String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 会话令牌摘要（SHA-256 十六进制，64 字符）：入库只存摘要，明文令牌只在登录响应出现一次 */
    public String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("令牌摘要失败：" + e.getMessage(), e);
        }
    }
}
