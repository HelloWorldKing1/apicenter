package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.CredentialRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * ACTIVE 条数（语义化命名：原 countByStatus 会让 prepare 之类误用含过期行的口径——E2 缺陷根因）。
     * 需要「未过期 ROTATING 条数」请用 {@link #countLiveRotating}。
     */
    public int countActive(String appId, String kind) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_credential WHERE app_id = ? AND kind = ? AND status = 'ACTIVE'",
                Integer.class, appId, kind);
        return n == null ? 0 : n;
    }

    /**
     * 未过期的 ROTATING 条数（E2，2026-09-11）：rotating_until 已过期的 ROTATING 在读取路径已惰性视为 RETIRED，
     * 不应再阻塞新轮换——原用 countByStatus 会把过期行也算进去，使 prepare 永久报「已有待激活的轮换凭证」。
     */
    public int countLiveRotating(String appId, String kind) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_credential
                WHERE app_id = ? AND kind = ? AND status = 'ROTATING'
                  AND (rotating_until IS NULL OR rotating_until > NOW())
                """, Integer.class, appId, kind);
        return n == null ? 0 : n;
    }

    /**
     * 批量查「存在 ACTIVE 凭证」的 app → kinds（E1，2026-09-11）：应用列表凭证角标用，一次 IN 查询避免 N+1。
     */
    public Map<String, Set<String>> findActiveKinds(List<String> appIds) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(appIds.size(), "?"));
        Map<String, Set<String>> result = new HashMap<>();
        jdbc.query("SELECT app_id, kind FROM app_credential WHERE status = 'ACTIVE' AND app_id IN ("
                        + placeholders + ")",
                // 显式声明为 RowCallbackHandler：否则与 ResultSetExtractor 重载二义
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.computeIfAbsent(rs.getString("app_id"), k -> new HashSet<>())
                                .add(rs.getString("kind")),
                appIds.toArray());
        return result;
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

    /**
     * 插入并回填自增 id（prepare 生成凭证需要返回真实 id 供前端「生成后立即激活」；
     * 原实现固定返回 id=-1，前端拿不到可操作目标——2026-09-12 修复）。
     */
    public long insertAndReturnId(CredentialRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO app_credential (app_id, kind, credential, status, rotating_until)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.appId());
            ps.setString(2, row.kind());
            ps.setString(3, row.credential());
            ps.setString(4, row.status());
            ps.setTimestamp(5, SqlTimes.ts(row.rotatingUntil()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("凭证插入未返回主键");
        }
        return key.longValue();
    }

    /** 状态流转：可同时更新 retired_at / rotating_until（传 null 表示不改） */
    public int updateStatus(long id, String status, LocalDateTime retiredAt, LocalDateTime rotatingUntil) {
        return jdbc.update("""
                UPDATE app_credential SET status = ?, retired_at = ?, rotating_until = ? WHERE id = ?
                """, status, retiredAt, rotatingUntil, id);
    }

    /**
     * CAS 式状态流转（并发安全，M0-04 状态机约束兜底）：仅当当前状态为 fromStatus 时更新。
     * 返回受影响行数（0 = 已被并发变更），调用方据此拒绝/重试，防止同 (app_id, kind) 双 ACTIVE / 双 ROTATING。
     */
    public int transitionStatus(long id, String fromStatus, String toStatus,
                                LocalDateTime retiredAt, LocalDateTime rotatingUntil) {
        return jdbc.update("""
                UPDATE app_credential SET status = ?, retired_at = ?, rotating_until = ?
                WHERE id = ? AND status = ?
                """, toStatus, retiredAt, rotatingUntil, id, fromStatus);
    }

    /** 物理删除凭证行（仅 RETIRED 可删，由 service 校验；ACTIVE/ROTATING 受保护） */
    public int delete(long id) {
        return jdbc.update("DELETE FROM app_credential WHERE id = ?", id);
    }

    /** 按 (app_id, kind) 批量置状态（reset：旧凭证全部 RETIRED） */
    public int retireAll(String appId, String kind) {
        return jdbc.update("""
                UPDATE app_credential SET status = 'RETIRED', retired_at = NOW(), rotating_until = NULL
                WHERE app_id = ? AND kind = ? AND status <> 'RETIRED'
                """, appId, kind);
    }
}
