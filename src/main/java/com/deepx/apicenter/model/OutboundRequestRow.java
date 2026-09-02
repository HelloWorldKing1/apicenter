package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * outbound_request 表行（Flow A 出站状态机载体，设计 §6.1）：
 * INIT → MAPPING → SENDING → RETRYING → COMPENSATING → SUCCESS / DEAD_LETTER / UNKNOWN。
 * 补偿 worker 按 (status, next_retry_at) 扫描；重放安全依赖上游对 biz_id 幂等（ADR 5）。
 */
public record OutboundRequestRow(
        long id, long interfaceId, String appId, String bizId,
        String inPayload, String outPayload, String respPayload,
        String status, int attemptCount, int maxAttempts,
        LocalDateTime nextRetryAt, String errorCode, String traceId,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {

    public static final RowMapper<OutboundRequestRow> MAPPER = (rs, i) -> new OutboundRequestRow(
            rs.getLong("id"),
            rs.getLong("interface_id"),
            rs.getString("app_id"),
            rs.getString("biz_id"),
            rs.getString("in_payload"),
            rs.getString("out_payload"),
            rs.getString("resp_payload"),
            rs.getString("status"),
            rs.getInt("attempt_count"),
            rs.getInt("max_attempts"),
            rs.getTimestamp("next_retry_at") == null ? null : rs.getTimestamp("next_retry_at").toLocalDateTime(),
            rs.getString("error_code"),
            rs.getString("trace_id"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );
}
