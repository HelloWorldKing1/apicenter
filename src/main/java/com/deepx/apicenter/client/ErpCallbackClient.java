package com.deepx.apicenter.client;

import com.deepx.apicenter.dto.OrderStatusCallbackDto;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * ERP 回调送达客户端 —— Flow B 中组件把第三方回调事件送达 ERP 的回调 URL（设计文档 §4）。
 *
 * <p>URL 来自 {@code callback_subscription}（示例中从 {@code ChannelProperties} 读 ERP 渠道 base-url，
 * 生产按订阅表路由）。返回 void 表示「HTTP 2xx 即视为 ack 成功」，非 2xx 抛异常由
 * {@code CallbackDeliveryService} 转补偿（PENDING → 补偿 worker 重发）。
 */
@HttpExchange
public interface ErpCallbackClient {

    /**
     * 送达 ERP 回调端点。
     *
     * @param event 已映射为 ERP 事件格式的回调 DTO
     */
    @PostExchange(value = "/api/erp/order-status", contentType = "application/json")
    void deliver(@RequestBody OrderStatusCallbackDto event);
}
