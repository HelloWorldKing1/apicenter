package com.deepx.apicenter.controller;

import com.deepx.apicenter.config.ChannelProperties;
import com.deepx.apicenter.dto.ErpOrderResponse;
import com.deepx.apicenter.dto.OrderDto;
import com.deepx.apicenter.service.OrderSyncService;
import com.deepx.apicenter.service.SignatureService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组件对 ERP 的统一接口（设计文档 §10.1）。
 *
 * <p>鉴权：ERP 调用需携带 {@code X-App-Id / X-Timestamp / X-Signature}，
 * 组件用 ERP 渠道密钥做 HMAC-SHA256 验签（时间戳容差 300s，设计文档 §7.3）。
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderSyncService orderSyncService;
    private final SignatureService signatureService;
    private final ChannelProperties channelProperties;

    public OrderController(OrderSyncService orderSyncService,
                           SignatureService signatureService,
                           ChannelProperties channelProperties) {
        this.orderSyncService = orderSyncService;
        this.signatureService = signatureService;
        this.channelProperties = channelProperties;
    }

    /**
     * Flow A 主入口：ERP 推送订单（POST /api/orders）。
     *
     * @param appId     ERP 应用标识
     * @param timestamp 请求时间戳（epoch 秒）
     * @param signature 签名（HMAC-SHA256(appId + timestamp + orderId, erp-secret)）
     * @param order     统一订单 JSON
     */
    @PostMapping("/orders")
    public ResponseEntity<ErpOrderResponse> pushOrder(
            @RequestHeader("X-App-Id") String appId,
            @RequestHeader("X-Timestamp") String timestamp,
            @RequestHeader("X-Signature") String signature,
            @RequestBody OrderDto order) {

        ChannelProperties.Channel erp = channelProperties.getChannels().get("ERP");
        String canonical = appId + timestamp + order.orderId();
        boolean ok = signatureService.verify(signature, canonical, erp.getSignatureSecret(),
                timestamp, channelProperties.getSignatureToleranceSeconds());
        if (!ok) {
            // 验签失败 → 401（设计文档 §3.2 步骤②）
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        ErpOrderResponse resp = orderSyncService.pushOrder(order);
        return ResponseEntity.ok(resp);
    }

    /**
     * 订单状态对账查询（UNKNOWN 对账入口，设计文档 §10.1 / §6.5）。
     */
    @PostMapping("/orders/query")
    public ResponseEntity<ErpOrderResponse> queryOrderStatus(@RequestBody OrderDto order) {
        return ResponseEntity.ok(orderSyncService.queryOrderStatus(order));
    }
}
