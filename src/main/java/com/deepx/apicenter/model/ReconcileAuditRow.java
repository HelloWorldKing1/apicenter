package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * reconcile_audit 表行（M4 交付，D-M4-2，第 17 张表）：对账操作审计留痕——
 * 人工置位（MANUAL）与 TTL 超时自动降级（TTL）均记录（谁、何时、UNKNOWN→?），满足 M0-03 §3.2。
 */
public record ReconcileAuditRow(
        long id, long outboundRequestId,
        String fromStatus, String toStatus,
        String source, String operator, String reason,
        LocalDateTime createdAt
) {

    public static final RowMapper<ReconcileAuditRow> MAPPER = (rs, i) -> new ReconcileAuditRow(
            rs.getLong("id"),
            rs.getLong("outbound_request_id"),
            rs.getString("from_status"),
            rs.getString("to_status"),
            rs.getString("source"),
            rs.getString("operator"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );
}
