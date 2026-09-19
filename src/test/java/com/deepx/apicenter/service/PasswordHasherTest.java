package com.deepx.apicenter.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 口令哈希单测（2026-09-18，纯单测无 Spring / 无 DB）：
 * PBKDF2-HMAC-SHA256 的「可验证 / 不可逆 / 同口令不同盐 / 格式健壮 / 令牌摘要」五件事。
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void 同一口令两次哈希不同_但都能校验通过() {
        String a = hasher.hash("Passw0rd!");
        String b = hasher.hash("Passw0rd!");

        assertThat(a).isNotEqualTo(b);              // 随机盐 ⇒ 相同口令不同摘要
        assertThat(hasher.verify("Passw0rd!", a)).isTrue();
        assertThat(hasher.verify("Passw0rd!", b)).isTrue();
    }

    @Test
    void 摘要不含明文且带算法与迭代次数前缀() {
        String hash = hasher.hash("Sup3rSecret");

        assertThat(hash).doesNotContain("Sup3rSecret");
        assertThat(hash).startsWith("pbkdf2$120000$");
        assertThat(hash.split("\\$")).hasSize(4);
    }

    @Test
    void 错误口令与大小写敏感() {
        String hash = hasher.hash("Passw0rd!");

        assertThat(hasher.verify("Passw0rd", hash)).isFalse();
        assertThat(hasher.verify("passw0rd!", hash)).isFalse();
        assertThat(hasher.verify("", hash)).isFalse();
        assertThat(hasher.verify(null, hash)).isFalse();
    }

    @Test
    void 存储值异常一律返回false不抛异常() {
        assertThat(hasher.verify("x", null)).isFalse();
        assertThat(hasher.verify("x", "")).isFalse();
        assertThat(hasher.verify("x", "plain-text")).isFalse();
        assertThat(hasher.verify("x", "pbkdf2$abc$xx$yy")).isFalse();
        assertThat(hasher.verify("x", "bcrypt$120000$xx$yy")).isFalse();
    }

    @Test
    void 令牌摘要为64位十六进制且稳定() {
        String h1 = hasher.sha256Hex("token-abc");
        String h2 = hasher.sha256Hex("token-abc");

        assertThat(h1).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(h1).isEqualTo(h2);
        assertThat(hasher.sha256Hex("token-abd")).isNotEqualTo(h1);
    }
}
