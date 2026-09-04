package com.deepx.apicenter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * call_log 数据访问（第 14 张表，M4 落地运行时语义）：异步批量写入 + 监控页查询 + 统计。
 */
@Repository
public class CallLogRepository {

    private final JdbcTemplate jdbc;

    public CallLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 异步批量写单元（CallLogWriter 攒批后提交） */
    public record CallLogEntry(
            String traceId, String spanId, String direction,
            Long interfaceId, String appId, String url, String method,
            Integer statusCode, Long latencyMs, String reqHeaders, String reqBody, String respBody) {
    }

    public record CallLogView(
            long id, String traceId, String direction, Long interfaceId, String appId,
            String url, String method, int statusCode, long latencyMs,
            String reqHeaders, String reqBody, String respBody, String createdAt) {
    }

    /** 批量插入（单事务内一批 ≤50 条，异步线程调用） */
    public void insertBatch(List<CallLogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO call_log (trace_id, span_id, direction, interface_id, app_id,
                                      url, method, status_code, latency_ms, req_headers, req_body, resp_body)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entries, 100, (PreparedStatement ps, CallLogEntry e) -> {
            ps.setString(1, e.traceId());
            ps.setString(2, e.spanId());
            ps.setString(3, e.direction());
            if (e.interfaceId() == null || e.interfaceId() <= 0) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, e.interfaceId());
            }
            ps.setString(5, e.appId());
            ps.setString(6, truncate(e.url(), 255));
            ps.setString(7, truncate(e.method(), 8));
            if (e.statusCode() == null) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, e.statusCode());
            }
            if (e.latencyMs() == null) {
                ps.setNull(9, java.sql.Types.BIGINT);
            } else {
                ps.setLong(9, e.latencyMs());
            }
            ps.setString(10, truncate(e.reqHeaders(), 2000));
            ps.setString(11, e.reqBody());
            ps.setString(12, e.respBody());
        });
    }

    /** 分页过滤（监控页调用日志，traceId / interfaceId 可空；倒序） */
    public List<CallLogView> findPaged(String traceId, Long interfaceId, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM call_log WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (traceId != null && !traceId.isBlank()) {
            sql.append(" AND trace_id = ?");
            args.add(traceId);
        }
        if (interfaceId != null && interfaceId > 0) {
            sql.append(" AND interface_id = ?");
            args.add(interfaceId);
        }
        sql.append(" ORDER BY id DESC LIMIT ").append(Math.max(1, limit))
                .append(" OFFSET ").append(Math.max(0, offset));
        return jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(CallLogRepository::toView).toList();
    }

    public long count(String traceId, Long interfaceId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM call_log WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (traceId != null && !traceId.isBlank()) {
            sql.append(" AND trace_id = ?");
            args.add(traceId);
        }
        if (interfaceId != null && interfaceId > 0) {
            sql.append(" AND interface_id = ?");
            args.add(interfaceId);
        }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** 今日网关入口流量（监控统计卡「今日调用量」，direction=IN） */
    public long countTodayIn() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM call_log WHERE direction = 'IN' AND created_at >= CURDATE()",
                Long.class);
        return n == null ? 0 : n;
    }

    /** 近 N 分钟出站调用 P99（AlertWorker p99_latency 指标：窗口内最新 5000 条的近似分位） */
    public long p99LatencyOut(int windowMinutes) {
        List<Long> latencies = jdbc.queryForList("""
                SELECT latency_ms FROM call_log
                WHERE direction = 'OUT' AND latency_ms IS NOT NULL
                  AND created_at >= DATE_SUB(NOW(), INTERVAL ? MINUTE)
                ORDER BY id DESC LIMIT 5000
                """, Long.class, windowMinutes);
        if (latencies.isEmpty()) {
            return 0;
        }
        List<Long> sorted = latencies.stream().sorted().toList();
        int idx = (int) Math.ceil(sorted.size() * 0.99) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static CallLogView toView(Map<String, Object> row) {
        return new CallLogView(
                ((Number) row.get("id")).longValue(),
                (String) row.get("trace_id"),
                (String) row.get("direction"),
                row.get("interface_id") == null ? null : ((Number) row.get("interface_id")).longValue(),
                (String) row.get("app_id"),
                (String) row.get("url"),
                (String) row.get("method"),
                row.get("status_code") == null ? 0 : ((Number) row.get("status_code")).intValue(),
                row.get("latency_ms") == null ? 0 : ((Number) row.get("latency_ms")).longValue(),
                (String) row.get("req_headers"),
                (String) row.get("req_body"),
                (String) row.get("resp_body"),
                row.get("created_at") == null ? null : row.get("created_at").toString());
    }
}
