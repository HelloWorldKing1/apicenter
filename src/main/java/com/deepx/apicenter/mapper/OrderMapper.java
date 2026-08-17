package com.deepx.apicenter.mapper;

import com.deepx.apicenter.dto.OrderDto;
import com.deepx.apicenter.dto.PartnerAOrderRequest;
import com.deepx.apicenter.dto.PartnerBOrderRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.math.BigDecimal;

/**
 * MapStruct 编译期映射器 —— 统一 {@link OrderDto} ⇄ 各渠道 DTO（设计文档 §5.4）。
 *
 * <p>核心价值：同一业务对象只写一次转换规则，编译期生成实现类（{@code OrderMapperImpl}），
 * 避免手写样板代码且类型安全。覆盖三类差异：
 * <ul>
 *   <li><b>金额单位</b>：元 → 分（{@link #yuanToCent}，@Named 方法）</li>
 *   <li><b>时间格式</b>：dateFormat 属性（ISO-8601 ↔ yyyyMMddHHmmss）</li>
 *   <li><b>平铺 → 嵌套</b>：buyerName/buyerPhone 聚合为嵌套 buyer；明细列表递归调用 toItemA/toItemB</li>
 * </ul>
 *
 * <p>说明：设计文档 §5.4 中的 {@code toErpResponse}（第三方响应 → ERP 响应）在本示例中改为由
 * {@code OrderSyncService} 直接调用 {@code ErpOrderResponse.success(...)} 静态工厂组装 ——
 * 因为 ERP 响应需注入业务默认值（code=0 / success=true / amount 回显），比强扭 MapStruct 更清晰。
 */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    // ==================== ERP 统一订单 → PARTNER_A（JSON） ====================

    /**
     * 统一订单 → PARTNER_A JSON 请求体。
     * 金额元→分；时间 ISO-8601；买家平铺→嵌套；地址/备注重命名。
     */
    @Mapping(source = "totalAmount", target = "totalAmountCent", qualifiedByName = "yuanToCent")
    @Mapping(source = "createdTime", target = "createdAt", dateFormat = "yyyy-MM-dd'T'HH:mm:ss")
    @Mapping(source = "buyerName", target = "buyer.name")
    @Mapping(source = "buyerPhone", target = "buyer.mobile")
    @Mapping(source = "shippingAddress", target = "receiver")
    @Mapping(source = "remark", target = "note")
    PartnerAOrderRequest toPartnerA(OrderDto dto);

    // ==================== ERP 统一订单 → PARTNER_B（XML） ====================

    /**
     * 统一订单 → PARTNER_B XML 请求体。
     * 金额元→分；状态值映射 NEW→1/PAID→2/SHIPPED→3；时间 yyyyMMddHHmmss。
     */
    @Mapping(source = "totalAmount", target = "totalAmount", qualifiedByName = "yuanToCent")
    @Mapping(source = "orderStatus", target = "status", qualifiedByName = "statusToInt")
    @Mapping(source = "createdTime", target = "createdTime", dateFormat = "yyyyMMddHHmmss")
    @Mapping(source = "buyerName", target = "buyer.name")
    @Mapping(source = "buyerPhone", target = "buyer.phone")
    @Mapping(source = "shippingAddress", target = "receiver")
    PartnerBOrderRequest toPartnerB(OrderDto dto);

    // ==================== 明细项映射（列表由 MapStruct 自动递归调用） ====================

    /**
     * ERP 明细 → PARTNER_A 明细（qty→quantity、单价/金额 元→分）。
     */
    @Mapping(source = "qty", target = "quantity")
    @Mapping(source = "unitPrice", target = "unitPriceCent", qualifiedByName = "yuanToCent")
    @Mapping(source = "amount", target = "amountCent", qualifiedByName = "yuanToCent")
    PartnerAOrderRequest.Item toItemA(OrderDto.OrderItemDto item);

    /**
     * ERP 明细 → PARTNER_B 明细（qty→quantity、单价/金额 元→分）。
     */
    @Mapping(source = "qty", target = "quantity")
    @Mapping(source = "unitPrice", target = "unitPrice", qualifiedByName = "yuanToCent")
    @Mapping(source = "amount", target = "amount", qualifiedByName = "yuanToCent")
    PartnerBOrderRequest.Item toItemB(OrderDto.OrderItemDto item);

    // ==================== 转换工具方法（@Named，供上述 @Mapping 引用） ====================

    /**
     * 金额换算：元 → 分（BigDecimal ×100 → long）。
     */
    @Named("yuanToCent")
    default long yuanToCent(BigDecimal yuan) {
        return yuan.multiply(BigDecimal.valueOf(100)).longValue();
    }

    /**
     * 状态值映射：NEW→1 / PAID→2 / SHIPPED→3（PARTNER_B XML 用数字状态）。
     */
    @Named("statusToInt")
    default int statusToInt(String status) {
        return switch (status == null ? "" : status) {
            case "NEW" -> 1;
            case "PAID" -> 2;
            case "SHIPPED" -> 3;
            default -> 0;
        };
    }
}
