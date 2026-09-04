package com.deepx.apicenter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * dead_letter 数据访问（M4 交付，D-M4-3）：查看 / 重放（状态重置 + HANDLED 置位）/ 堆积计数。
 * 写入侧（insertDeadLetter / countDeadLetter）保留在 OutboundRequestRepository（引擎既有路径）。
 */
@Repository
public class DeadLetterRepository {

    private final JdbcTemplate jdbc;

    public DeadLetterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DeadLetterView(
            long id, String bizType, Long refId, String reason, String payload,
            String status, String handledAt, String createdAt) {
    }

    public Optional<DeadLetterView> findById(long id) {
        return query("WHERE id = " + id, 1, 0).stream().findFirst();
    }

    /** 分页过滤（bizType / status 可空 = 不过滤） */
    public List<DeadLetterView> findPaged(String bizType, String status, int offset, int limit) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        if (bizType != null && !bizType.isBlank()) {
            where.append(" AND biz_type = '").append(bizType.replace("'", "")).append("'");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = '").append(status.replace("'", "")).append("'");
        }
        return query(where.toString(), limit, offset);
    }

    public long count(String bizType, String status) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        if (bizType != null && !bizType.isBlank()) {
            where.append(" AND biz_type = '").append(bizType.replace("'", "")).append("'");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = '").append(status.replace("'", "")).append("'");
        }
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter " + where, Long.class);
        return n == null ? 0 : n;
    }

    /** PENDING 堆积数（AlertWorker dead_letter_backlog 指标） */
    public long countPending() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter WHERE status = 'PENDING'", Long.class);
        return n == null ? 0 : n;
    }

    /** 重放后置位：HANDLED + 处理时间。仅 PENDING 可置位（防重：已处理的重放在 Service 层拒绝） */
    public int markHandled(long id) {
        return jdbc.update("UPDATE dead_letter SET status = 'HANDLED', handled_at = NOW() WHERE id = ?", id);
    }

    private List<DeadLetterView> query(String whereClause, int limit, int offset) {
        return jdbc.queryForList("SELECT * FROM dead_letter " + whereClause
                        + " ORDER BY id DESC LIMIT " + Math.max(1, limit) + " OFFSET " + Math.max(0, offset))
                .stream().map(DeadLetterRepository::toView).toList();
    }

    private static DeadLetterView toView(Map<String, Object> row) {
        return new DeadLetterView(
                ((Number) row.get("id")).longValue(),
                (String) row.get("biz_type"),
                row.get("ref_id") == null ? null : ((Number) row.get("ref_id")).longValue(),
                (String) row.get("reason"),
                (String) row.get("payload"),
                (String) row.get("status"),
                row.get("handled_at") == null ? null : row.get("handled_at").toString(),
                row.get("created_at") == null ? null : row.get("created_at").toString());
    }
}
