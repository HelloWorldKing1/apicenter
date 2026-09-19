package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * 管理面账号（admin_user，2026-09-18）。
 * 注意：口令摘要**不**进入 {@link View}（避免任何一处误把摘要返回给前端）。
 */
public record AdminUserRow(
        long id,
        String username,
        String displayName,
        String passwordHash,
        String status,
        int failedAttempts,
        LocalDateTime lockedUntil,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt) {

    public static final RowMapper<AdminUserRow> MAPPER = (rs, i) -> new AdminUserRow(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("password_hash"),
            rs.getString("status"),
            rs.getInt("failed_attempts"),
            rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toLocalDateTime(),
            rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toLocalDateTime(),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());

    /** 对外视图（无口令摘要） */
    public record View(long id, String username, String displayName, LocalDateTime lastLoginAt) {
        public static View of(AdminUserRow row) {
            return new View(row.id(), row.username(), row.displayName(), row.lastLoginAt());
        }
    }

    /**
     * 账号管理列表行（2026-09-18）：多带「有效会话数」与时间字段，供账号管理页展示。
     * 仍**不含**口令摘要（列表接口永不返回摘要）。
     */
    public record ListRow(long id, String username, String displayName, String status, int failedAttempts,
                          LocalDateTime lockedUntil, LocalDateTime lastLoginAt, LocalDateTime passwordUpdatedAt,
                          LocalDateTime createdAt, int sessionCount) {

        public static final RowMapper<ListRow> LIST_MAPPER = (rs, i) -> new ListRow(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getInt("failed_attempts"),
                toTime(rs.getTimestamp("locked_until")),
                toTime(rs.getTimestamp("last_login_at")),
                toTime(rs.getTimestamp("password_updated_at")),
                toTime(rs.getTimestamp("created_at")),
                rs.getInt("session_count"));
    }

    private static LocalDateTime toTime(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
