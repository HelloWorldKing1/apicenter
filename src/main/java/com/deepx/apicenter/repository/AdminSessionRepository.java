package com.deepx.apicenter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * admin_session 数据访问（管理面会话令牌，2026-09-18）。
 * 只存令牌的 SHA-256 摘要：库泄露不等于可直接登录（与 M0-04「凭证不落明文」同一纪律）。
 */
@Repository
public class AdminSessionRepository {

    private final JdbcTemplate jdbc;

    public AdminSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long userId, String tokenHash, String clientInfo, LocalDateTime expiresAt) {
        jdbc.update("""
                        INSERT INTO admin_session (user_id, token_hash, client_info, expires_at, last_seen_at, renewed_at)
                        VALUES (?, ?, ?, ?, NOW(), NOW())
                        """,
                userId, tokenHash, clientInfo, Timestamp.valueOf(expiresAt));
    }

    /** 令牌 → 账号（仅未过期的会话）；用 JOIN 一次拿到账号主体 */
    public java.util.Optional<Long> findUserIdByToken(String tokenHash) {
        return jdbc.queryForList(
                "SELECT user_id FROM admin_session WHERE token_hash = ? AND expires_at > NOW()",
                Long.class, tokenHash).stream().findFirst();
    }

    /**
     * 惰性续期（单条 UPDATE）：仅当「距上次续期超过阈值」才写——把判据放在 SQL 里，
     * 避免「先读一次判断、再写一次」的两次往返；效果 = 最多 1 次写 / 会话 / 阈值间隔（控制写放大）。
     */
    public int renewIfDue(String tokenHash, LocalDateTime newExpiresAt, LocalDateTime threshold) {
        return jdbc.update("""
                UPDATE admin_session SET expires_at = ?, renewed_at = NOW(), last_seen_at = NOW()
                WHERE token_hash = ? AND renewed_at <= ?
                """, Timestamp.valueOf(newExpiresAt), tokenHash, Timestamp.valueOf(threshold));
    }

    public int deleteByToken(String tokenHash) {
        return jdbc.update("DELETE FROM admin_session WHERE token_hash = ?", tokenHash);
    }

    /** 改密后吊销该账号**其他**会话（当前会话保留，避免刚改完就被踢出） */
    public int deleteByUserExcept(long userId, String keepTokenHash) {
        return jdbc.update("DELETE FROM admin_session WHERE user_id = ? AND token_hash <> ?", userId, keepTokenHash);
    }

    /** 按账号删除全部会话（重置口令 / 删除账号 / 停用账号时调用 → 该账号所有设备立即掉线） */
    public int deleteByUserId(long userId) {
        return jdbc.update("DELETE FROM admin_session WHERE user_id = ?", userId);
    }

    /** 机会式清理过期会话（登录时调用；无定时任务，避免为一个几乎不增长的表引入调度） */
    public int deleteExpired() {
        return jdbc.update("DELETE FROM admin_session WHERE expires_at <= NOW()");
    }
}
