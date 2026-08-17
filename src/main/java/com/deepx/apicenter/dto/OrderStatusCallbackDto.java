package com.deepx.apicenter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Flow B 入站回调事件 —— 第三方回调组件端点时反序列化的载体（设计文档 §4.1 / §10.2）。
 *
 * <p>同时复用作「送达 ERP 的回调事件」：{@code source} 标记来源渠道（如 PARTNER_A），
 * ERP 侧据此区分回调来源。映射动作见 {@code CallbackDeliveryService} 与 {@code OrderMapper}。
 *
 * @param event   事件类型（如 order.status.changed）
 * @param orderNo 第三方订单号
 * @param status  第三方侧状态（如 SHIPPED）
 * @param source  来源渠道代码（组件填充，标识是哪个第三方回调）
 */
public record OrderStatusCallbackDto(
        @JsonProperty("event") String event,
        @JsonProperty("orderNo") String orderNo,
        @JsonProperty("status") String status,
        @JsonProperty("source") String source) {
}
