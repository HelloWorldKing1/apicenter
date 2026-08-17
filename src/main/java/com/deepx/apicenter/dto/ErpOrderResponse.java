package com.deepx.apicenter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * 组件返回给 ERP 的统一响应体（Flow A 出站结果 / 失败 ack）。
 *
 * <p>code=0 且 success=true 表示成功；业务错误（如 400 死信）时 success=false 并附
 * {@code deadLetterId} 供 ERP 侧按管理端点查询死信详情（与 demo 页 badRequest 场景的失败 ack 一致）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErpOrderResponse(
        @JsonProperty("code") int code,
        @JsonProperty("success") boolean success,
        @JsonProperty("thirdOrderNo") String thirdOrderNo,
        @JsonProperty("thirdStatus") String thirdStatus,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("message") String message,
        @JsonProperty("deadLetterId") Long deadLetterId) {

    /**
     * 构造成功响应：第三方订单号 / 第三方状态 / 反向换算后的金额（元）。
     */
    public static ErpOrderResponse success(String thirdOrderNo, String thirdStatus, BigDecimal amount) {
        return new ErpOrderResponse(0, true, thirdOrderNo, thirdStatus, amount, null, null);
    }

    /**
     * 构造失败响应：业务码（如 40001）、错误消息、可选死信 ID。
     */
    public static ErpOrderResponse failure(int code, String message, Long deadLetterId) {
        return new ErpOrderResponse(code, false, null, null, null, message, deadLetterId);
    }
}
