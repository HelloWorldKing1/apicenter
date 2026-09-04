package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.ReconcileAuditRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * reconcile_audit 数据访问（M4 交付，D-M4-2）：对账审计写入与查询。
 */
@Repository
public class ReconcileAuditRepository {

    private final JdbcTemplate jdbc;

    public ReconcileAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long outboundRequestId, String fromStatus, String toStatus,
                       String source, String operator, String reason) {
        jdbc.update("""
                INSERT INTO reconcile_audit (outbound_request_id, from_status, to_status, source, operator, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, outboundRequestId, fromStatus, toStatus, source, operator, reason);
    }

    /** 按出站记录查审计轨迹（一个 UNKNOWN 可能被 TTL 降级后再人工修正，全历史保留） */
    public List<ReconcileAuditRow> findByOutboundRequest(long outboundRequestId) {
        return jdbc.query("SELECT * FROM reconcile_audit WHERE outbound_request_id = ? ORDER BY id",
                ReconcileAuditRow.MAPPER, outboundRequestId);
    }

    /** 最近审计（监控页展示） */
    public List<ReconcileAuditRow> findRecent(int limit) {
        return jdbc.query("SELECT * FROM reconcile_audit ORDER BY id DESC LIMIT ?",
                ReconcileAuditRow.MAPPER, limit);
    }
}
