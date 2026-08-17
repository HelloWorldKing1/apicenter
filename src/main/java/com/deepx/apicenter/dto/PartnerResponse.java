package com.deepx.apicenter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 第三方通用响应 —— PARTNER_A / PARTNER_B 的返回折叠为这个通用结构，便于反向映射
 * （设计文档 §3.1 步骤⑥ / §5.4 toErpResponse）。
 *
 * <p>注意：PARTNER_B 若返回 XML，标签名需与 {@link JsonProperty} 对齐或为该渠道单独建一个
 * 带 {@code @JacksonXmlProperty} 的响应 DTO。此处为保持示例精简，统一用 JSON 键名。
 *
 * @param code    第三方业务码（0 通常表示成功）
 * @param message 第三方消息
 * @param orderNo 第三方侧订单号（对应 ERP 的 orderId）
 * @param status  第三方侧状态（如 CREATED）
 */
public record PartnerResponse(
        @JsonProperty("code") int code,
        @JsonProperty("message") String message,
        @JsonProperty("orderNo") String orderNo,
        @JsonProperty("status") String status) {
}
