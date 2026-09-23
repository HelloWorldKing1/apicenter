package com.deepx.apicenter.aspect;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏器（M4 交付，D-M4-4）：call_log 落库前完成（内存中不留原始值）。
 * - Header：敏感键（不区分大小写）→ 值替换「前 4 + **** + 后 4」，长度 &lt; 12 全遮；
 * - Body：大陆手机号 → 前 3 + **** + 后 4；敏感 JSON 键（secret/token/password/apikey/api_key/credential）
 *   的字符串值 → 同遮蔽规则；
 * - 截断：req/resp_body 超 4096 字符截断加后缀（1MB 报文全量落库不可取）。
 */
@Component
public class SensitiveDataMasker {

    /** body 截断阈值（字符） */
    public static final int BODY_TRUNCATE_CHARS = 4096;

    /**
     * 敏感头名集合（小写）。**可动态注册**（2026-09-23，入站鉴权 B2 / D-CA-13）：
     * 头名如今可被配置（调用方鉴权的凭证头、签名头都可能改名），写死的清单必然漏 ——
     * 因此闸门在解析适配器 params 后调用 {@link #registerHeader(String)} 把实际头名登记进来。
     * 用并发集合：注册可能发生在请求线程（多请求并发），读也并发。
     */
    private static final Set<String> SENSITIVE_HEADERS = buildSensitiveHeaders();

    /** 大陆手机号（前后非数字边界） */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");

    /** 敏感 JSON 键值对（键名匹配 → 字符串值遮蔽） */
    private static final Pattern SENSITIVE_JSON_VALUE = Pattern.compile(
            "(\"[^\"]*(?i:secret|token|password|apikey|api_key|credential)[^\"]*\"\\s*:\\s*\")([^\"]{0,200})(\")");

    private static Set<String> buildSensitiveHeaders() {
        Set<String> keys = java.util.concurrent.ConcurrentHashMap.newKeySet();
        keys.addAll(java.util.List.of("authorization", "proxy-authorization", "x-api-key", "x-auth-token",
                "x-access-token", "cookie", "set-cookie", "x-partner-signature", "x-signature",
                // 入站鉴权（调用方）默认头名
                "x-client-id", "x-internal-token"));
        return keys;
    }

    /**
     * 动态登记需脱敏的头名（大小写不敏感）。由调用方鉴权闸门在解析适配器 params 后调用
     * （凭证头 / 签名头 / 时间戳头都可能被配置改名）。空值忽略。
     */
    public static void registerHeader(String name) {
        if (name != null && !name.isBlank()) {
            SENSITIVE_HEADERS.add(name.trim().toLowerCase());
        }
    }

    /** Header 集合脱敏：返回可落库的字符串形如 "k1: v1 | k2: v2"（保持插入序） */
    public String maskHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(e.getKey()).append(": ")
                    .append(isSensitiveHeader(e.getKey()) ? maskValue(e.getValue()) : e.getValue());
        }
        return sb.toString();
    }

    /** 请求 / 响应体脱敏 + 截断：输入字节按 UTF-8 解码 */
    public String maskBody(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return maskBody(text);
    }

    /** 请求 / 响应体脱敏 + 截断（字符串形态） */
    public String maskBody(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String masked = PHONE.matcher(text).replaceAll(m -> maskPhone(m.group(1)));
        masked = SENSITIVE_JSON_VALUE.matcher(masked).replaceAll(
                m -> m.group(1) + maskValue(m.group(2)) + m.group(3));
        if (masked.length() > BODY_TRUNCATE_CHARS) {
            return masked.substring(0, BODY_TRUNCATE_CHARS) + "...[truncated]";
        }
        return masked;
    }

    /** 值遮蔽：前 4 + **** + 后 4；长度 &lt; 12 全遮（短值无可辨识信息保留价值） */
    String maskValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() < 12) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private boolean isSensitiveHeader(String name) {
        return name != null && SENSITIVE_HEADERS.contains(name.toLowerCase());
    }
}
