package com.deepx.apicenter.service;

import com.deepx.apicenter.config.CryptoProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 凭证加解密纯单测（无 Spring 上下文）：M0-04 §3.4 AES-256-GCM 约定。
 */
class CryptoServiceTest {

    /** 32 字节测试密钥的 base64 编码（与生产密钥同格式） */
    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void 加密解密往返() {
        CryptoService svc = new CryptoService(new CryptoProperties(TEST_KEY));
        String plain = "fastmoss-test-token";
        assertThat(svc.decrypt(svc.encrypt(plain))).isEqualTo(plain);
    }

    @Test
    void 密文不含明文() {
        CryptoService svc = new CryptoService(new CryptoProperties(TEST_KEY));
        String cipher = svc.encrypt("fastmoss-test-token");
        assertThat(cipher).doesNotContain("fastmoss");
        assertThat(cipher).isNotEqualTo("fastmoss-test-token");
    }

    @Test
    void 每次加密密文不同_随机IV() {
        CryptoService svc = new CryptoService(new CryptoProperties(TEST_KEY));
        assertThat(svc.encrypt("same-plain")).isNotEqualTo(svc.encrypt("same-plain"));
    }

    @Test
    void 指纹为尾四位() {
        CryptoService svc = new CryptoService(new CryptoProperties(TEST_KEY));
        assertThat(svc.fingerprint("fastmoss-test-token")).isEqualTo("oken");
        assertThat(svc.fingerprint("abc")).isEqualTo("abc"); // 短于 4 位原样
    }

    @Test
    void 密文被篡改解密失败() {
        CryptoService svc = new CryptoService(new CryptoProperties(TEST_KEY));
        String cipher = svc.encrypt("secret-value");
        String tampered = (cipher.charAt(0) == 'A' ? 'B' : 'A') + cipher.substring(1);
        assertThatThrownBy(() -> svc.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 密钥缺失启动失败() {
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝明文裸跑");
    }

    @Test
    void 密钥长度非法启动失败() {
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties("c2hvcnQ="))) // 5 字节
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }
}
