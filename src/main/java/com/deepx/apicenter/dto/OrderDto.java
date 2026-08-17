package com.deepx.apicenter.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一订单模型 —— 组件内部的「唯一业务对象」（设计文档 §5.1）。
 *
 * <p>全链路复用的中间语言：
 * <ul>
 *   <li><b>出站（Flow A）</b>：ERP 调用组件时，请求 JSON 直接反序列化为本对象；再经 {@code OrderMapper}
 *       映射为 PARTNER_A(JSON) / PARTNER_B(XML) 渠道格式。</li>
 *   <li><b>入站（Flow B）</b>：第三方回调事件映射为 ERP 事件时，以本对象为对齐基准。</li>
 * </ul>
 *
 * <p>金额一律用 {@link BigDecimal}（元，scale=2）；映射到第三方时由 MapStruct 的
 * {@code yuanToCent} 方法 ×100 转成分（long）。
 *
 * @param orderId         ERP 订单号（唯一）
 * @param orderType       业务类型：PUSH（推送）/ PULL（拉取）
 * @param orderStatus     订单状态：NEW / PAID / SHIPPED（映射到 PARTNER_B 时值映射为 1/2/3）
 * @param totalAmount     总金额（元，scale=2）
 * @param currency        币种（CNY）
 * @param buyerName       买家姓名（平铺；映射时聚合为嵌套 buyer.name / buyer.mobile）
 * @param buyerPhone      买家手机（平铺）
 * @param createdTime     创建时间（映射时格式化为各渠道时间格式：ISO-8601 / yyyyMMddHHmmss）
 * @param items           订单明细（嵌套列表，递归映射）
 * @param shippingAddress 收货地址（映射时重命名为 receiver）
 * @param remark          备注（映射时重命名为 note / Remark）
 */
public record OrderDto(
        String orderId,
        String orderType,
        String orderStatus,
        BigDecimal totalAmount,
        String currency,
        String buyerName,
        String buyerPhone,
        LocalDateTime createdTime,
        List<OrderItemDto> items,
        AddressDto shippingAddress,
        String remark) {

    /**
     * 订单明细项（嵌套在 OrderDto 内，避免额外文件）。
     *
     * @param skuCode   商品编码
     * @param qty       数量（ERP 侧命名，映射到渠道侧 quantity）
     * @param unitPrice 单价（元），映射到渠道侧 unitPriceCent（分）
     * @param amount    行金额（元），映射到渠道侧 amountCent（分）
     */
    public record OrderItemDto(String skuCode, Integer qty, BigDecimal unitPrice, BigDecimal amount) {
    }

    /**
     * 收货地址（嵌套在 OrderDto 内）。
     *
     * @param province 省
     * @param city     市
     * @param detail   详细地址
     */
    public record AddressDto(String province, String city, String detail) {
    }
}
