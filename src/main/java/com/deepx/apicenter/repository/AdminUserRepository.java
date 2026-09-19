package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.AdminUserRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * admin_user 数据访问（管理面账号，2026-09-18）。
 * 口令只存 PBKDF2 摘要（{@code PasswordHasher}）；登录失败/锁定计数落库（多实例共享同一口径）。
 */
@Repository
public class AdminUserRepository {

    private static final String COLS = """
            SELECT id, username, display_name, password_hash, status, failed_attempts,
                   locked_until, last_login_at, created_at
            FROM admin_user
            """;

    private final JdbcTemplate jdbc;

    public AdminUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AdminUserRow> findByUsername(String username) {
        return jdbc.query(COLS + " WHERE username = ?", AdminUserRow.MAPPER, username).stream().findFirst();
    }

    public Optional<AdminUserRow> findById(long id) {
        return jdbc.query(COLS + " WHERE id = ?", AdminUserRow.MAPPER, id).stream().findFirst();
    }

    /** 账号总数（注册开关的「首次初始化」判据） */
    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class);
        return n == null ? 0 : n;
    }

    public long insert(String username, String displayName, String passwordHash) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO admin_user (username, display_name, password_hash, password_updated_at) VALUES (?, ?, ?, NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, displayName);
            ps.setString(3, passwordHash);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("账号写入未回填主键（admin_user）");
        }
        return key.longValue();
    }

    /** 登录成功：清零失败计数 + 记录最近登录时间（一次 UPDATE） */
    public void recordLoginSuccess(long id) {
        jdbc.update("UPDATE admin_user SET failed_attempts = 0, locked_until = NULL, last_login_at = NOW() WHERE id = ?", id);
    }

    /** 登录失败：累加计数；达到阈值时写入锁定截止时间 */
    public void recordLoginFailure(long id, int failedAttempts, LocalDateTime lockedUntil) {
        jdbc.update("UPDATE admin_user SET failed_attempts = ?, locked_until = ? WHERE id = ?",
                failedAttempts, lockedUntil == null ? null : Timestamp.valueOf(lockedUntil), id);
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.update("UPDATE admin_user SET password_hash = ?, password_updated_at = NOW(), failed_attempts = 0, locked_until = NULL WHERE id = ?",
                passwordHash, id);
    }
}
