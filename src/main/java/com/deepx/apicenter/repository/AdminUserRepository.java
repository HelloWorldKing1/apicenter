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
            SELECT id, username, display_name, password_hash, role, status, failed_attempts,
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

    /**
     * 账号管理列表（keyword 模糊匹配用户名 / 显示名）。
     * 会话数用相关子查询一次带出（账号数量级很小，不值得引入 JOIN + GROUP BY 的复杂度）。
     */
    public java.util.List<AdminUserRow.ListRow> findAll(String keyword) {
        String kw = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        String sql = """
                SELECT u.id, u.username, u.display_name, u.role, u.status, u.failed_attempts, u.locked_until,
                       u.last_login_at, u.password_updated_at, u.created_at,
                       (SELECT COUNT(*) FROM admin_session s WHERE s.user_id = u.id AND s.expires_at > NOW()) AS session_count
                FROM admin_user u
                """;
        if (kw == null) {
            return jdbc.query(sql + " ORDER BY u.id", AdminUserRow.ListRow.LIST_MAPPER);
        }
        return jdbc.query(sql + " WHERE u.username LIKE ? OR u.display_name LIKE ? ORDER BY u.id",
                AdminUserRow.ListRow.LIST_MAPPER, kw, kw);
    }

    /** 可用（ENABLED）账号数：账号管理守卫「不能停用/删除最后一个可用账号」的判据 */
    public int countEnabled() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user WHERE status = 'ENABLED'", Integer.class);
        return n == null ? 0 : n;
    }

    /** 账号总数（注册开关的「首次初始化」判据） */
    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class);
        return n == null ? 0 : n;
    }

    public long insert(String username, String displayName, String passwordHash, String role) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO admin_user (username, display_name, password_hash, role, password_updated_at) VALUES (?, ?, ?, ?, NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, displayName);
            ps.setString(3, passwordHash);
            ps.setString(4, role);
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

    /** 改资料：显示名 + 状态（账号管理「编辑 / 启停用」） */
    public void updateProfile(long id, String displayName, String status) {
        jdbc.update("UPDATE admin_user SET display_name = ?, status = ? WHERE id = ?", displayName, status, id);
    }

    /** 变更角色（仅 OWNER 可调用，守卫在 service） */
    public void updateRole(long id, String role) {
        jdbc.update("UPDATE admin_user SET role = ? WHERE id = ?", role, id);
    }

    /** 仍具备 OWNER 角色的账号数：守卫「不能降级/删除最后一个 OWNER」（否则没人能再管账号/分配角色） */
    public int countOwners() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_user WHERE role = 'OWNER' AND status = 'ENABLED'", Integer.class);
        return n == null ? 0 : n;
    }

    /** 解除锁定（清失败计数与锁定时间）——暴力破解误伤后的自助恢复口 */
    public void unlock(long id) {
        jdbc.update("UPDATE admin_user SET failed_attempts = 0, locked_until = NULL WHERE id = ?", id);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM admin_user WHERE id = ?", id);
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.update("UPDATE admin_user SET password_hash = ?, password_updated_at = NOW(), failed_attempts = 0, locked_until = NULL WHERE id = ?",
                passwordHash, id);
    }
}
