package com.deepx.apicenter.aspect;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏器单测（M4 计划 §4）：Header 敏感键遮蔽 / 短值全遮 / 手机号 / 敏感 JSON 键 / 截断 / 非敏感不动。
 */
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void 敏感Header遮蔽_保留前4后4() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer abcdefghijklmnop");
        headers.put("Content-Type", "application/json");
        String masked = masker.maskHeaders(headers);
        assertThat(masked).contains("Authorization: Bear****mnop");
        assertThat(masked).contains("Content-Type: application/json"); // 非敏感原样
        assertThat(masked).doesNotContain("abcdefghijklmnop");
    }

    @Test
    void Header大小写不敏感() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "0123456789abcdef");
        assertThat(masker.maskHeaders(headers)).contains("0123****cdef");
    }

    @Test
    void 短敏感值全遮() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-API-Key", "short");
        assertThat(masker.maskHeaders(headers)).contains("X-API-Key: ****");
    }

    @Test
    void 手机号脱敏_前3后4() {
        String body = "{\"contact\":\"13812345678\",\"name\":\"张三\"}";
        String masked = masker.maskBody(body);
        assertThat(masked).contains("138****5678");
        assertThat(masked).doesNotContain("13812345678");
        assertThat(masked).contains("张三");
    }

    @Test
    void 手机号边界_长数字串不误伤() {
        // 12 位以上数字串不匹配 11 位手机号模式（前后非数字边界）
        String body = "{\"order\":\"138123456789\"}";
        String masked = masker.maskBody(body);
        assertThat(masked).contains("138123456789");
    }

    @Test
    void 敏感JSON键值遮蔽() {
        String body = "{\"client_secret\":\"my-very-long-secret-value\",\"token\":\"abcdef1234567890\",\"count\":5}";
        String masked = masker.maskBody(body);
        assertThat(masked).doesNotContain("my-very-long-secret-value");
        assertThat(masked).doesNotContain("abcdef1234567890");
        assertThat(masked).contains("\"count\":5");
    }

    @Test
    void 键名部分匹配也遮蔽() {
        String body = "{\"access_token_value\":\"abcdefghijklmnopqrstuvwxyz\"}";
        assertThat(masker.maskBody(body)).doesNotContain("abcdefghijklmnopqrstuvwxyz");
    }

    @Test
    void 超长截断加后缀() {
        String body = "x".repeat(5000);
        String masked = masker.maskBody(body.getBytes(StandardCharsets.UTF_8));
        assertThat(masked).hasSize(SensitiveDataMasker.BODY_TRUNCATE_CHARS + "...[truncated]".length());
        assertThat(masked).endsWith("...[truncated]");
    }

    @Test
    void 空输入返回null() {
        assertThat(masker.maskBody((byte[]) null)).isNull();
        assertThat(masker.maskBody(new byte[0])).isNull();
        assertThat(masker.maskHeaders(null)).isNull();
        assertThat(masker.maskHeaders(Map.of())).isNull();
    }

    @Test
    void 非敏感报文原样保留() {
        String body = "{\"hello\":\"world\",\"n\":123}";
        assertThat(masker.maskBody(body)).isEqualTo(body);
    }
}
