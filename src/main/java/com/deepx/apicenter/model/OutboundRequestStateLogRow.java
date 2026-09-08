package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * outbound_request_state_log 表行（M5 后状态链，事件溯源 append-only，设计 §4.6）：
 * 每次出站状态「真正变化」追加一行（from_status → to_status + trigger + detail + 变更时 attempt/error_code）。
 * outbound_request.status 只存当前值，本表是其历史镜像，供监控页状态链可视化与故障定位。
 *
 * <p>注意：MySQL 保留字规避——物理列名 trigger_src（逻辑名 trigger），仅 DDL/SQL 层差异。
 */
public record OutboundRequestStateLogRow(
        long id, long outboundRequestId, int seq,
        String fromStatus, String toStatus,
        int attempt, String errorCode,
        String trigger, String detail, String traceId,
        LocalDateTime createdAt
) {

    public static final RowMapper<OutboundRequestStateLogRow> MAPPER = (rs, i) -> new OutboundRequestStateLogRow(
            rs.getLong("id"),
            rs.getLong("outbound_request_id"),
            rs.getInt("seq"),
            rs.getString("from_status"),
            rs.getString("to_status"),
            rs.getInt("attempt"),
            rs.getString("error_code"),
            rs.getString("trigger_src"),
            rs.getString("detail"),
            rs.getString("trace_id"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );
}
