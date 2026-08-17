package com.deepx.apicenter.service;

import com.deepx.apicenter.client.ErpCallbackClient;
import com.deepx.apicenter.config.ChannelProperties;
import com.deepx.apicenter.dto.OrderStatusCallbackDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Flow B 入站回调编排 —— 验签 → 查订阅 → 映射 → 送达 ERP → 回 ack（设计文档 §4）。
 *
 * <p>送达失败时落 {@code callback_delivery(PENDING)}，由 {@code CompensationWorker} 按
 * {@code retry-worker-fixed-delay-ms}（3000ms）周期重发；对第三方的 ack 照常返回 200，
 * 避免第三方无谓重推（设计文档 §4.2 步骤⑤、§6.2 RETRYING 分支）。
 */
@Service
public class CallbackDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(CallbackDeliveryService.class);

    private final ErpCallbackClient erpCallbackClient;
    private final ChannelProperties channelProperties;
    private final JdbcTemplate jdbcTemplate;

    public CallbackDeliveryService(ErpCallbackClient erpCallbackClient,
                                   ChannelProperties channelProperties,
                                   JdbcTemplate jdbcTemplate) {
        this.erpCallbackClient = erpCallbackClient;
        this.channelProperties = channelProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 处理第三方回调（已通过验签）。
     *
     * @param channel 渠道代码（如 PARTNER_A）
     * @param event   渠道回调事件
     * @return 回给第三方的 ack 消息
     */
    public String handleCallback(String channel, OrderStatusCallbackDto event) {
        // 1) 映射：渠道回调 DTO → ERP 回调事件 DTO（标记来源渠道；字段映射见 OrderMapper / demo 步骤③）
        OrderStatusCallbackDto erpEvent =
                new OrderStatusCallbackDto(event.event(), event.orderNo(), event.status(), channel);

        // 2) 落 callback_delivery(RECEIVED)
        long deliveryId = insertDelivery(channel, erpEvent);

        // 3) 送达 ERP（@HttpExchange：2xx 即 ack 成功；非 2xx 抛异常）
        try {
            erpCallbackClient.deliver(erpEvent);
            updateDelivery(deliveryId, "ERP_ACKED");
            log.info("回调 {} 已送达 ERP，deliveryId={}", channel, deliveryId);
            return "ok";
        } catch (ResourceAccessException | HttpServerErrorException e) {
            // 4) 送达失败：置 PENDING 交补偿 worker 重发；对第三方仍回 ack
            updateDelivery(deliveryId, "PENDING");
            log.warn("送达 ERP 失败，deliveryId={}, err={}；进入补偿队列", deliveryId, e.getMessage());
            return "ok";
        }
    }

    // ==================== JdbcTemplate 落库（示例级） ====================

    private long insertDelivery(String channel, OrderStatusCallbackDto event) {
        jdbcTemplate.update("""
                INSERT INTO callback_delivery
                  (subscription_id, callback_event_id, payload, delivery_status, attempt_count, created_at)
                VALUES (?,?,?,?,?,?)
                """,
                1L, event.orderNo(), event.toString(), "RECEIVED", 0,
                Timestamp.valueOf(LocalDateTime.now()));
        return 1L; // 示例简化：固定 deliveryId=1；生产取回自增主键
    }

    private void updateDelivery(long deliveryId, String status) {
        jdbcTemplate.update("UPDATE callback_delivery SET delivery_status = ? WHERE id = ?", status, deliveryId);
    }

    /**
     * 供 CompensationWorker 调用：按 deliveryId 重放送达。
     */
    public void redeliver(long deliveryId, OrderStatusCallbackDto erpEvent) {
        erpCallbackClient.deliver(erpEvent);
        updateDelivery(deliveryId, "ERP_ACKED");
    }
}
