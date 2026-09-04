package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * alert_rule 表行（第 15 张表，M4 落地运行时语义）：阈值告警规则——
 * metric ∈ success_rate / p99_latency / dead_letter_backlog / retry_backlog；
 * threshold 表达式 = "&lt;op&gt; &lt;number&gt;"（op ∈ &lt; &lt;= &gt; &gt;=，如 "&lt; 95"、"&gt; 100"）。
 */
public record AlertRuleRow(
        long id, String name, String metric, String threshold,
        String notifyChannel, boolean enabled,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {

    public static final RowMapper<AlertRuleRow> MAPPER = (rs, i) -> new AlertRuleRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("metric"),
            rs.getString("threshold"),
            rs.getString("notify_channel"),
            rs.getBoolean("enabled"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );
}
