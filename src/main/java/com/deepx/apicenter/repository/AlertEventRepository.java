package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.AlertEventRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * alert_event 数据访问（M4 交付，D-M4-5）：告警事件写入与分页查询。
 */
@Repository
public class AlertEventRepository {

    private final JdbcTemplate jdbc;

    public AlertEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long ruleId, String metric, String level, String message, String context) {
        jdbc.update("""
                INSERT INTO alert_event (rule_id, metric, level, message, context)
                VALUES (?, ?, ?, ?, ?)
                """, ruleId, metric, level, message, context);
    }

    /** 分页（监控页告警列表，倒序） */
    public List<AlertEventRow> findPaged(int offset, int limit) {
        return jdbc.query("SELECT * FROM alert_event ORDER BY id DESC LIMIT ? OFFSET ?",
                AlertEventRow.MAPPER, limit, offset);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM alert_event", Long.class);
        return n == null ? 0 : n;
    }
}
