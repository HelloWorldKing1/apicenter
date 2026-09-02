package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.CredentialRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * app_credential 表数据访问（M0-04 凭证轮换存储方案）。
 * 状态机约束（应用层保证）：每 (app_id, kind) 的 ACTIVE 至多 1、ROTATING 至多 1。
 */
@Repository
public class CredentialRepository {

    private final JdbcTemplate jdbc;

    public CredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CredentialRow> findByApp(String appId) {
        return jdbc.query(
                "SELECT * FROM app_credential WHERE app_id = ? ORDER BY kind, created_at DESC",
                CredentialRow.MAPPER, appId);
    }

    public Optional<CredentialRow> findById(long id) {
        return jdbc.query("SELECT * FROM app_credential WHERE id = ?", CredentialRow.MAPPER, id).stream().findFirst();
    }

    /** 出站签名用：仅 ACTIVE（M0-04 §3.2 读取规则；M2 链引擎调用） */
    public Optional<CredentialRow> findActive(String appId, String kind) {
        return jdbc.query(
                "SELECT * FROM app_credential WHERE app_id = ? AND kind = ? AND status = 'ACTIVE'",
                CredentialRow.MAPPER, appId, kind).stream().findFirst();
    }

    /**
     * 回调验签用：ACTIVE + ROTATING 全部（未过 rotating_until 的 ROTATING 有效，
     * 过期惰性视为 RETIRED；逐个试、任一命中即通过，M0-04 §3.2）。M2 链引擎调用。
     */
    public List<CredentialRow> findVerifiable(String appId, String kind) {
        return jdbc.query("""
                SELECT * FROM app_credential
                WHERE app_id = ? AND kind = ?
                  AND (status = 'ACTIVE'
                       OR (status = 'ROTATING' AND (rotating_until IS NULL OR rotating_until > NOW())))
                ORDER BY status = 'ACTIVE' DESC, created_at DESC
                """, CredentialRow.MAPPER, appId, kind);
    }

    public int countByStatus(String appId, String kind, String status) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_credential WHERE app_id = ? AND kind = ? AND status = ?",
                Integer.class, appId, kind, status);
        return n == null ? 0 : n;
    }

    /** 是否存在明文未加密的凭证（单测断言用：库中不得出现明文） */
    public int countByCredentialText(String appId, String plaintext) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_credential WHERE app_id = ? AND credential = ?",
                Integer.class, appId, plaintext);
        return n == null ? 0 : n;
    }

    public int insert(CredentialRow row) {
        return jdbc.update("""
                INSERT INTO app_credential (app_id, kind, credential, status, rotating_until)
                VALUES (?, ?, ?, ?, ?)
                """, row.appId(), row.kind(), row.credential(), row.status(), row.rotatingUntil());
    }

    /** 状态流转：可同时更新 retired_at / rotating_until（传 null 表示不改） */
    public int updateStatus(long id, String status, LocalDateTime retiredAt, LocalDateTime rotatingUntil) {
        return jdbc.update("""
                UPDATE app_credential SET status = ?, retired_at = ?, rotating_until = ? WHERE id = ?
                """, status, retiredAt, rotatingUntil, id);
    }

    /** 按 (app_id, kind) 批量置状态（reset：旧凭证全部 RETIRED） */
    public int retireAll(String appId, String kind) {
        return jdbc.update("""
                UPDATE app_credential SET status = 'RETIRED', retired_at = NOW(), rotating_until = NULL
                WHERE app_id = ? AND kind = ? AND status <> 'RETIRED'
                """, appId, kind);
    }
}
