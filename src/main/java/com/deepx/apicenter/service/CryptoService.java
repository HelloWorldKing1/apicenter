package com.deepx.apicenter.service;

import com.deepx.apicenter.config.CryptoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭证可逆加解密（M0-04 §3.4）：AES-256-GCM。
 * 密文格式：base64(iv ‖ ciphertext)（GCM 认证 tag 内置于 ciphertext）。
 * 设计 §1.2 约束：凭证不得单向哈希——签名 / 验签需明文重算，必须可逆。
 */
@Service
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoService {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(CryptoProperties props) {
        if (props.key() == null || props.key().isBlank()) {
            throw new IllegalStateException(
                    "缺少凭证加密密钥：配置 app.api-center.crypto.key 或环境变量 APICENTER_CRYPTO_KEY（base64 编码 32 字节）。拒绝明文裸跑。");
        }
        byte[] raw = Base64.getDecoder().decode(props.key().trim());
        if (raw.length != 32) {
            throw new IllegalStateException("凭证加密密钥必须为 32 字节（AES-256）");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** 加密：返回 base64(iv ‖ ciphertext) */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("凭证加密失败", e);
        }
    }

    /** 解密 base64(iv ‖ ciphertext) */
    public String decrypt(String ciphertext) {
        try {
            byte[] in = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_LEN));
            byte[] pt = cipher.doFinal(in, IV_LEN, in.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("凭证解密失败（密钥不匹配或数据损坏）", e);
        }
    }

    /** 指纹：尾 4 位，用于管理面遮显回显（M0-04 §3.2） */
    public String fingerprint(String plaintext) {
        if (plaintext == null || plaintext.length() <= 4) {
            return plaintext;
        }
        return plaintext.substring(plaintext.length() - 4);
    }
}
