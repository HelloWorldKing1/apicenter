package com.deepx.apicenter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * call_log 数据访问（第 14 张表，M4 落地运行时语义）：异步批量写入 + 监控页查询 + 统计。
 */
@Repository
public class CallLogRepository {

    /** 列表投影列（不含 body） */
    private static final String LIST_COLUMNS =
            "id, trace_id, direction, interface_id, app_id, url, method, status_code, latency_ms, created_at";

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

    /** 分页过滤（监控页调用日志，全部条件可空；id 倒序）。Monitor 升级（v0.2）：direction / appId / HTTP 码区间 / 时间窗 / url 子串 */
    public List<CallLogView> findPaged(String traceId, Long interfaceId, String direction, String appId,
                                       Integer statusMin, Integer statusMax,
                                       LocalDateTime timeFrom, LocalDateTime timeTo, String keyword,
                                       int offset, int limit) {
        // 列表只投影元数据列（req_body/resp_body 为 LONGTEXT，列表用不到——详情走 findById，2026-09-12 瘦身）
        StringBuilder sql = new StringBuilder("SELECT " + LIST_COLUMNS + " FROM call_log WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, traceId, interfaceId, direction, appId, statusMin, statusMax, timeFrom, timeTo, keyword);
        sql.append(" ORDER BY id DESC LIMIT ").append(Math.max(1, limit))
                .append(" OFFSET ").append(Math.max(0, offset));
        return jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(CallLogRepository::toView).toList();
    }

    /** 详情：按 id 取单条（含 req_body / resp_body 全量） */
    public java.util.Optional<CallLogView> findById(long id) {
        return jdbc.queryForList("SELECT * FROM call_log WHERE id = ?", id).stream()
                .map(CallLogRepository::toView)
                .findFirst();
    }

    public long count(String traceId, Long interfaceId, String direction, String appId,
                      Integer statusMin, Integer statusMax,
                      LocalDateTime timeFrom, LocalDateTime timeTo, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM call_log WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, traceId, interfaceId, direction, appId, statusMin, statusMax, timeFrom, timeTo, keyword);
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, String traceId, Long interfaceId,
                                      String direction, String appId, Integer statusMin, Integer statusMax,
                                      LocalDateTime timeFrom, LocalDateTime timeTo, String keyword) {
        if (traceId != null && !traceId.isBlank()) {
            sql.append(" AND trace_id = ?");
            args.add(traceId);
        }
        if (interfaceId != null && interfaceId > 0) {
            sql.append(" AND interface_id = ?");
            args.add(interfaceId);
        }
        if (direction != null && !direction.isBlank()) {
            sql.append(" AND direction = ?");
            args.add(direction);
        }
        if (appId != null && !appId.isBlank()) {
            sql.append(" AND app_id = ?");
            args.add(appId);
        }
        if (statusMin != null) {
            sql.append(" AND status_code >= ?");
            args.add(statusMin);
        }
        if (statusMax != null) {
            sql.append(" AND status_code < ?");
            args.add(statusMax);
        }
        if (timeFrom != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.valueOf(timeFrom));
        }
        if (timeTo != null) {
            sql.append(" AND created_at < ?");
            args.add(Timestamp.valueOf(timeTo));
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND url LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
    }

    /** 某方向在时间窗内按「真实分钟桶」计数（仪表盘趋势：IN/OUT 调用量；空窗返回空 Map） */
    public Map<Long, Long> countByMinute(String direction, String appId,
                                         LocalDateTime from, LocalDateTime to) {
        StringBuilder sql = new StringBuilder("""
                SELECT UNIX_TIMESTAMP(created_at) DIV 60 AS b, COUNT(*) AS c
                FROM call_log WHERE direction = ? AND created_at >= ? AND created_at < ?""");
        List<Object> args = new ArrayList<>(List.of(direction, Timestamp.valueOf(from), Timestamp.valueOf(to)));
        if (appId != null && !appId.isBlank()) {
            sql.append(" AND app_id = ?");
            args.add(appId);
        }
        sql.append(" GROUP BY b");
        return jdbc.queryForList(sql.toString(), args.toArray()).stream().collect(Collectors.toMap(
                r -> ((Number) r.get("b")).longValue(),
                r -> ((Number) r.get("c")).longValue(),
                (a, b) -> a, LinkedHashMap::new));
    }

    /** 出站调用延迟行（OUT，按窗口内最新 N 条近似；供延迟趋势与 TOP 接口 P50/P99） */
    public List<OutLatencyRow> outLatencies(LocalDateTime from, LocalDateTime to, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT interface_id, UNIX_TIMESTAMP(created_at) DIV 60 AS b, latency_ms
                FROM call_log
                WHERE direction = 'OUT' AND latency_ms IS NOT NULL
                  AND created_at >= ? AND created_at < ?
                ORDER BY id DESC LIMIT ?
                """, Timestamp.valueOf(from), Timestamp.valueOf(to), Math.max(1, limit));
        List<OutLatencyRow> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(new OutLatencyRow(
                    r.get("interface_id") == null ? null : ((Number) r.get("interface_id")).longValue(),
                    ((Number) r.get("b")).longValue(),
                    ((Number) r.get("latency_ms")).longValue()));
        }
        return out;
    }

    /** 出站（方向 OUT）调用量按接口在窗口内计数（TOP 接口） */
    public Map<Long, Long> countOutByInterface(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList("""
                SELECT interface_id, COUNT(*) AS c FROM call_log
                WHERE direction = 'OUT' AND interface_id IS NOT NULL
                  AND created_at >= ? AND created_at < ?
                GROUP BY interface_id
                """, Timestamp.valueOf(from), Timestamp.valueOf(to)).stream().collect(Collectors.toMap(
                r -> ((Number) r.get("interface_id")).longValue(),
                r -> ((Number) r.get("c")).longValue(),
                (a, b) -> a, LinkedHashMap::new));
    }

    /** 网关入口（IN）调用量按接口在窗口内计数（TOP 接口） */
    public Map<Long, Long> countInByInterface(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList("""
                SELECT interface_id, COUNT(*) AS c FROM call_log
                WHERE direction = 'IN' AND interface_id IS NOT NULL
                  AND created_at >= ? AND created_at < ?
                GROUP BY interface_id
                """, Timestamp.valueOf(from), Timestamp.valueOf(to)).stream().collect(Collectors.toMap(
                r -> ((Number) r.get("interface_id")).longValue(),
                r -> ((Number) r.get("c")).longValue(),
                (a, b) -> a, LinkedHashMap::new));
    }

    /** 出站延迟行（接口 + 真实分钟桶 + 延迟，延迟趋势/TOP 共用） */
    public record OutLatencyRow(Long interfaceId, long bucketMinute, long latencyMs) {
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
