package com.deepx.apicenter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * PARTNER_A（JSON 渠道）的订单推送请求体 —— 体现「命名差异 / 金额元→分 / 平铺→嵌套」三类映射
 * （设计文档 §5.2 / §5.3）。
 *
 * <p>字段键名通过 {@link JsonProperty} 显式固定，保证 JSON 序列化键名确定；
 * 金额一律用 {@code long}（分）存储，与 ERP 侧 {@link OrderDto} 的 {@code BigDecimal}（元）由
 * MapStruct 的 {@code yuanToCent} 换算。
 *
 * @param orderNo        订单号（ERP 的 orderId 重命名）
 * @param orderType      业务类型（直传）
 * @param orderStatus    订单状态（直传，仅 PARTNER_B 做值映射）
 * @param totalAmountCent 总金额（分，ERP 的 totalAmount ×100）
 * @param currency       币种（直传）
 * @param buyer          买家（ERP 平铺的 buyerName/buyerPhone 聚合而成）
 * @param createdAt      创建时间（ISO-8601 字符串，MapStruct dateFormat 转换）
 * @param items          明细列表（金额已 ×100 为分）
 * @param receiver       收货地址（ERP 的 shippingAddress 重命名）
 * @param note           备注（ERP 的 remark 重命名）
 */
public record PartnerAOrderRequest(
        @JsonProperty("orderNo") String orderNo,
        @JsonProperty("orderType") String orderType,
        @JsonProperty("orderStatus") String orderStatus,
        @JsonProperty("totalAmountCent") long totalAmountCent,
        @JsonProperty("currency") String currency,
        @JsonProperty("buyer") Buyer buyer,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("items") List<Item> items,
        @JsonProperty("receiver") Receiver receiver,
        @JsonProperty("note") String note) {

    /**
     * 买家 —— ERP 侧平铺的 buyerName / buyerPhone 在此聚合为嵌套对象。
     */
    public record Buyer(
            @JsonProperty("name") String name,
            @JsonProperty("mobile") String mobile) {
    }

    /**
     * 明细项 —— 由 ERP 的 {@link OrderItemDto} 映射而来，金额已 ×100 为分。
     */
    public record Item(
            @JsonProperty("skuCode") String skuCode,
            @JsonProperty("quantity") int quantity,
            @JsonProperty("unitPriceCent") long unitPriceCent,
            @JsonProperty("amountCent") long amountCent) {
    }

    /**
     * 收货地址 —— 由 ERP 的 {@link OrderDto.AddressDto} 重命名而来。
     */
    public record Receiver(
            @JsonProperty("province") String province,
            @JsonProperty("city") String city,
            @JsonProperty("detail") String detail) {
    }
}
