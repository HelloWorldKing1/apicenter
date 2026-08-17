package com.deepx.apicenter.dto;

import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

/**
 * PARTNER_B（XML 渠道）的订单推送请求体 —— 体现 XML 的 PascalCase 命名 / 状态值映射 / 时间格式差异
 * （设计文档 §5.2 / §5.3）。
 *
 * <p>依赖 {@code jackson-dataformat-xml}（见 pom.xml）。根元素为 {@code OrderPushRequest}；
 * 字段用 {@link JacksonXmlProperty} 固定标签名；明细列表加
 * {@link JacksonXmlElementWrapper}(useWrapping = false) 去掉外层包裹，让 {@code <Item>} 平铺为根子元素。
 *
 * @param orderNo     订单号（XML 标签 OrderNo，PascalCase）
 * @param orderType   业务类型（OrderType，直传）
 * @param status      订单状态（Status，值映射 NEW→1 / PAID→2 / SHIPPED→3）
 * @param totalAmount 总金额（TotalAmount，分）
 * @param currency    币种（Currency，直传）
 * @param buyer       买家（Buyer，含 Name/Phone 子标签）
 * @param createdTime 创建时间（CreatedTime，yyyyMMddHHmmss，MapStruct dateFormat 转换）
 * @param items       明细列表（Item 平铺，无外层包裹）
 * @param receiver    收货地址（Receiver）
 * @param remark      备注（Remark）
 */
@JacksonXmlRootElement(localName = "OrderPushRequest")
public record PartnerBOrderRequest(
        @JacksonXmlProperty(localName = "OrderNo") String orderNo,
        @JacksonXmlProperty(localName = "OrderType") String orderType,
        @JacksonXmlProperty(localName = "Status") int status,
        @JacksonXmlProperty(localName = "TotalAmount") long totalAmount,
        @JacksonXmlProperty(localName = "Currency") String currency,
        @JacksonXmlProperty(localName = "Buyer") Buyer buyer,
        @JacksonXmlProperty(localName = "CreatedTime") String createdTime,
        @JacksonXmlProperty(localName = "Item")
        @JacksonXmlElementWrapper(useWrapping = false)
        List<Item> items,
        @JacksonXmlProperty(localName = "Receiver") Receiver receiver,
        @JacksonXmlProperty(localName = "Remark") String remark) {

    /**
     * 买家 —— XML 标签 <Buyer><Name>、<Buyer><Phone>。
     */
    public record Buyer(
            @JacksonXmlProperty(localName = "Name") String name,
            @JacksonXmlProperty(localName = "Phone") String phone) {
    }

    /**
     * 明细项 —— XML <Item>，金额为分。
     */
    public record Item(
            @JacksonXmlProperty(localName = "SkuCode") String skuCode,
            @JacksonXmlProperty(localName = "Quantity") int quantity,
            @JacksonXmlProperty(localName = "UnitPrice") long unitPrice,
            @JacksonXmlProperty(localName = "Amount") long amount) {
    }

    /**
     * 收货地址 —— XML <Receiver>。
     */
    public record Receiver(
            @JacksonXmlProperty(localName = "Province") String province,
            @JacksonXmlProperty(localName = "City") String city,
            @JacksonXmlProperty(localName = "Detail") String detail) {
    }
}
