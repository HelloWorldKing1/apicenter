package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.AlertRuleRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * alert_rule 数据访问（第 15 张表，M4 落地运行时语义）：告警规则 CRUD + 启用规则加载。
 */
@Repository
public class AlertRuleRepository {

    private final JdbcTemplate jdbc;

    public AlertRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AlertRuleRow> findEnabled() {
        return jdbc.query("SELECT * FROM alert_rule WHERE enabled = 1 ORDER BY id",
                AlertRuleRow.MAPPER);
    }

    public List<AlertRuleRow> findAll() {
        return jdbc.query("SELECT * FROM alert_rule ORDER BY id", AlertRuleRow.MAPPER);
    }

    public Optional<AlertRuleRow> findById(long id) {
        return jdbc.query("SELECT * FROM alert_rule WHERE id = ?", AlertRuleRow.MAPPER, id)
                .stream().findFirst();
    }

    public void insert(String name, String metric, String threshold, String notifyChannel, boolean enabled) {
        jdbc.update("""
                INSERT INTO alert_rule (name, metric, threshold, notify_channel, enabled)
                VALUES (?, ?, ?, ?, ?)
                """, name, metric, threshold, notifyChannel, enabled);
    }

    public void update(long id, String name, String metric, String threshold,
                       String notifyChannel, boolean enabled) {
        jdbc.update("""
                UPDATE alert_rule SET name = ?, metric = ?, threshold = ?, notify_channel = ?, enabled = ?
                WHERE id = ?
                """, name, metric, threshold, notifyChannel, enabled, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM alert_rule WHERE id = ?", id);
    }
}
