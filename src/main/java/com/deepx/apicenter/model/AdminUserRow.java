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
}
