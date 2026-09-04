package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * alert_event 表行（M4 交付，D-M4-5，第 18 张表）：告警事件持久化——
 * alert_rule 触发记录 + 内置告警（验签连续失败等，rule_id 为 NULL）；监控页展示与冷却判重。
 */
public record AlertEventRow(
        long id, Long ruleId, String metric, String level, String message, String context,
        LocalDateTime createdAt
) {

    public static final RowMapper<AlertEventRow> MAPPER = (rs, i) -> new AlertEventRow(
            rs.getLong("id"),
            rs.getObject("rule_id") == null ? null : rs.getLong("rule_id"),
            rs.getString("metric"),
            rs.getString("level"),
            rs.getString("message"),
            rs.getString("context"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );
}
