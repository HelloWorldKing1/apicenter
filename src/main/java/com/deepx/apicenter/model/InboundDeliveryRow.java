package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * inbound_delivery 表行（Flow B 送达状态机载体，设计 §6.1）：
 * delivery_status：RECEIVED（保留不启用）/ ACKED（送达成功）/ PENDING（待重送）/ DEAD_LETTER（重送耗尽）。
 * 定稿 D-M3-2：首期落库即 PENDING（next_retry_at=now+5s 防 worker 抢跑）、送达成功转 ACKED；
 * 重放按 payload 快照 + callback_url_snapshot（不重新走链、不随接口改址漂移）。
 */
public record InboundDeliveryRow(
        long id, long interfaceId, String appId, String callbackEventId,
        String payload, String callbackUrlSnapshot, String deliveryStatus,
        int attemptCount, int maxAttempts, LocalDateTime nextRetryAt,
        String ackToPartner, String traceId,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {

    public static final RowMapper<InboundDeliveryRow> MAPPER = (rs, i) -> new InboundDeliveryRow(
            rs.getLong("id"),
            rs.getLong("interface_id"),
            rs.getString("app_id"),
            rs.getString("callback_event_id"),
            rs.getString("payload"),
            rs.getString("callback_url_snapshot"),
            rs.getString("delivery_status"),
            rs.getInt("attempt_count"),
            rs.getInt("max_attempts"),
            rs.getTimestamp("next_retry_at") == null ? null : rs.getTimestamp("next_retry_at").toLocalDateTime(),
            rs.getString("ack_to_partner"),
            rs.getString("trace_id"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );
}
