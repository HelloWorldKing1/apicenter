package com.deepx.apicenter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * `access_auth_log` 数据访问（2026-09-23，入站鉴权 B3；设计方案 §10）。
 *
 * <p>**只写 + 少量按 trace 查询**（列表/摘要端点属 B4）：写入统一走 {@code AccessAuthLogWriter} 的异步批量，
 * 主链路不阻塞（与 `call_log` 同纪律）。
 */
@Repository
public class AccessAuthLogRepository {

    private final JdbcTemplate jdbc;

    public AccessAuthLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 审计行（字段与表一一对应）。`result` / `auth_method` / `direction` / `principal_type` NOT NULL，
     * 其余可空（主体未识别、无 UA、无 XFF 等）。
     */
    public record AccessAuthLogEntry(
            String traceId, String direction, String principalType, String principalId, String principalName,
            Long interfaceId, String interfaceCode, String authMethod, String authAdapterId,
            String result, String errorCode, String reason,
            String clientIp, String xffChain, String userAgent, Long latencyMs) {
    }

    public void insertBatch(List<AccessAuthLogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO access_auth_log (trace_id, direction, principal_type, principal_id, principal_name,
                                             interface_id, interface_code, auth_method, auth_adapter_id,
                                             result, error_code, reason, client_ip, xff_chain, user_agent, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entries, 100, (PreparedStatement ps, AccessAuthLogEntry e) -> {
            ps.setString(1, truncate(e.traceId(), 32));
            ps.setString(2, truncate(e.direction(), 16));
            ps.setString(3, truncate(e.principalType(), 16));
            ps.setString(4, truncate(e.principalId(), 32));
            ps.setString(5, truncate(e.principalName(), 64));
            if (e.interfaceId() == null || e.interfaceId() <= 0) {
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, e.interfaceId());
            }
            ps.setString(7, truncate(e.interfaceCode(), 16));
            // NOT NULL 列兜底：异常路径也可能给出 null，落库不能因此失败
            ps.setString(8, e.authMethod() == null || e.authMethod().isBlank() ? "NONE" : truncate(e.authMethod(), 24));
            ps.setString(9, truncate(e.authAdapterId(), 16));
            ps.setString(10, e.result() == null || e.result().isBlank() ? "REJECT" : truncate(e.result(), 8));
            ps.setString(11, truncate(e.errorCode(), 16));
            ps.setString(12, truncate(e.reason(), 255));
            ps.setString(13, truncate(e.clientIp(), 45));
            ps.setString(14, truncate(e.xffChain(), 255));
            ps.setString(15, truncate(e.userAgent(), 200));
            if (e.latencyMs() == null) {
                ps.setNull(16, java.sql.Types.BIGINT);
            } else {
                ps.setLong(16, e.latencyMs());
            }
        });
    }

    /** 按 traceId 查审计行（三方串联：call_log / 运行表 / 审计；B4 的监控页也走这里） */
    public List<AccessAuthLogEntry> findByTrace(String traceId) {
        return jdbc.query("SELECT * FROM access_auth_log WHERE trace_id = ? ORDER BY id", (rs, i) ->
                new AccessAuthLogEntry(
                        rs.getString("trace_id"), rs.getString("direction"), rs.getString("principal_type"),
                        rs.getString("principal_id"), rs.getString("principal_name"),
                        rs.getObject("interface_id") == null ? null : rs.getLong("interface_id"),
                        rs.getString("interface_code"), rs.getString("auth_method"), rs.getString("auth_adapter_id"),
                        rs.getString("result"), rs.getString("error_code"), rs.getString("reason"),
                        rs.getString("client_ip"), rs.getString("xff_chain"), rs.getString("user_agent"),
                        rs.getObject("latency_ms") == null ? null : rs.getLong("latency_ms")), traceId);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
