package com.deepx.apicenter.controller;

import com.deepx.apicenter.config.ChannelProperties;
import com.deepx.apicenter.dto.OrderStatusCallbackDto;
import com.deepx.apicenter.service.CallbackDeliveryService;
import com.deepx.apicenter.service.SignatureService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 第三方回调组件端点（Flow B 入站，设计文档 §10.2）。
 *
 * <p>回调路径 {@code /callback/{channel}/order-status}：第三方带签名回调 → 验签 → 映射 → 送达 ERP。
 * 验签失败返回 401 并记录 DROPPED（设计文档 §4.1 alt 分支）。
 */
@RestController
@RequestMapping("/callback")
public class CallbackController {

    private final CallbackDeliveryService callbackDeliveryService;
    private final SignatureService signatureService;
    private final ChannelProperties channelProperties;

    public CallbackController(CallbackDeliveryService callbackDeliveryService,
                              SignatureService signatureService,
                              ChannelProperties channelProperties) {
        this.callbackDeliveryService = callbackDeliveryService;
        this.signatureService = signatureService;
        this.channelProperties = channelProperties;
    }

    /**
     * 第三方订单状态回调。
     *
     * @param channel   渠道代码（PATH 变量，如 PARTNER_A）
     * @param signature 第三方签名（X-Partner-Signature）
     * @param timestamp 第三方时间戳（X-Timestamp）
     * @param event     回调事件体
     */
    @PostMapping("/{channel}/order-status")
    public ResponseEntity<Map<String, Object>> orderStatus(
            @PathVariable String channel,
            @RequestHeader(value = "X-Partner-Signature", required = false) String signature,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestBody OrderStatusCallbackDto event) {

        // 验签：用该渠道的签名密钥（示例从配置读；生产从 callback_subscription 表读 map_rule）
        ChannelProperties.Channel ch = channelProperties.getChannels().get(channel);
        String canonical = (timestamp == null ? "" : timestamp) + event.orderNo();
        boolean ok = signatureService.verify(signature, canonical, ch.getSignatureSecret(),
                timestamp == null ? "" : timestamp, channelProperties.getSignatureToleranceSeconds());
        if (!ok) {
            // 验签失败 → DROPPED（设计文档 §6.2）
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "msg", "signature invalid"));
        }

        String ack = callbackDeliveryService.handleCallback(channel, event);
        return ResponseEntity.ok(Map.of("code", 0, "msg", ack));
    }
}
