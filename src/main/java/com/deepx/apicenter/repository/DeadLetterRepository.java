package com.deepx.apicenter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * dead_letter 数据访问（M4 交付，D-M4-3）：查看 / 重放（状态重置 + HANDLED 置位）/ 堆积计数。
 * 写入侧（insertDeadLetter / countDeadLetter）保留在 OutboundRequestRepository（引擎既有路径）。
 *
 * <p>2026-09-18 修复（代码评审 P2）：过滤条件的值一律走**参数绑定**。原实现把 `bizType/status`
 * 拼进 SQL 字面量、只做 `replace("'", "")` 剥离单引号——反斜杠仍可让字面量未闭合（MySQL 默认
 * `NO_BACKSLASH_ESCAPES` 关闭），触发语法错误 500。
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

    private static final RowMapper<DeadLetterView> MAPPER = (rs, i) -> new DeadLetterView(
            rs.getLong("id"), rs.getString("biz_type"),
            rs.getObject("ref_id") == null ? null : rs.getLong("ref_id"),
            rs.getString("reason"), rs.getString("payload"), rs.getString("status"),
            rs.getString("handled_at"), rs.getString("created_at"));

    public Optional<DeadLetterView> findById(long id) {
        return jdbc.query("SELECT * FROM dead_letter WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** 分页过滤（bizType / status 可空 = 不过滤；全参数化，见类注释的修复说明） */
    public List<DeadLetterView> findPaged(String bizType, String status, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(bizType, status, args);
        return jdbc.query("SELECT * FROM dead_letter" + where
                        + " ORDER BY id DESC LIMIT " + Math.max(1, limit) + " OFFSET " + Math.max(0, offset),
                MAPPER, args.toArray());
    }

    public long count(String bizType, String status) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(bizType, status, args);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter" + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** 过滤条件拼装：只拼占位符与关键字，值由调用方 args 绑定（不拼接任何用户输入） */
    private String buildWhere(String bizType, String status, List<Object> args) {
        StringBuilder where = new StringBuilder();
        if (bizType != null && !bizType.isBlank()) {
            where.append(where.isEmpty() ? " WHERE" : " AND").append(" biz_type = ?");
            args.add(bizType);
        }
        if (status != null && !status.isBlank()) {
            where.append(where.isEmpty() ? " WHERE" : " AND").append(" status = ?");
            args.add(status);
        }
        return where.toString();
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
}
