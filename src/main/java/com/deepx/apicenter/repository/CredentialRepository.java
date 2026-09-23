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
 * 凭证表数据访问（M0-04 凭证轮换存储方案）——**两类属主共用**（2026-09-23 起，入站鉴权 B1 / D-CA-15 方案 A）。
 *
 * <p>表名 / 属主列名取自 {@link CredentialOwner} 的**常量**（`app_credential.app_id` /
 * `client_credential.client_id`）⇒ 拼进 SQL 安全，且**一套机制两处复用**（不再复制 ~150 行）。
 *
 * <p>状态机约束（应用层保证）：每 `(属主, kind)` 的 `ACTIVE` 至多 1、`ROTATING` 至多 1。
 *
 * <p>**兼容**：原 `findByApp` / `findById` 等应用侧入口保留为委托，M0-04 的既有调用点零改动。
 */
@Repository
public class CredentialRepository {

    private final JdbcTemplate jdbc;

    public CredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 应用侧（兼容入口，全部委托到属主通用实现） ----------

    public List<CredentialRow> findByApp(String appId) {
        return findByOwner(CredentialOwner.APP, appId);
    }

    public Optional<CredentialRow> findById(long id) {
        return findById(CredentialOwner.APP, id);
    }

    public Optional<CredentialRow> findActive(String appId, String kind) {
        return findActive(CredentialOwner.APP, appId, kind);
    }

    public List<CredentialRow> findVerifiable(String appId, String kind) {
        return findVerifiable(CredentialOwner.APP, appId, kind);
    }

    public int countActive(String appId, String kind) {
        return countActive(CredentialOwner.APP, appId, kind);
    }

    public int countLiveRotating(String appId, String kind) {
        return countLiveRotating(CredentialOwner.APP, appId, kind);
    }

    public Map<String, Set<String>> findActiveKinds(List<String> appIds) {
        return findActiveKinds(CredentialOwner.APP, appIds);
    }

    public int countByCredentialText(String appId, String plaintext) {
        return countByCredentialText(CredentialOwner.APP, appId, plaintext);
    }

    public int insert(CredentialRow row) {
        return insert(CredentialOwner.APP, row);
    }

    public long insertAndReturnId(CredentialRow row) {
        return insertAndReturnId(CredentialOwner.APP, row);
    }

    public int updateStatus(long id, String status, LocalDateTime retiredAt, LocalDateTime rotatingUntil) {
        return updateStatus(CredentialOwner.APP, id, status, retiredAt, rotatingUntil);
    }

    public int transitionStatus(long id, String fromStatus, String toStatus,
                                LocalDateTime retiredAt, LocalDateTime rotatingUntil) {
        return transitionStatus(CredentialOwner.APP, id, fromStatus, toStatus, retiredAt, rotatingUntil);
    }

    public int delete(long id) {
        return delete(CredentialOwner.APP, id);
    }

    public int retireAll(String appId, String kind) {
        return retireAll(CredentialOwner.APP, appId, kind);
    }

    // ---------- 属主通用实现 ----------

    public List<CredentialRow> findByOwner(CredentialOwner owner, String ownerId) {
        return jdbc.query("SELECT * FROM " + owner.table() + " WHERE " + owner.ownerPredicate() + " ORDER BY kind, created_at DESC", owner.rowMapper(), ownerId);
    }

    public Optional<CredentialRow> findById(CredentialOwner owner, long id) {
        return jdbc.query("SELECT * FROM " + owner.table() + " WHERE id = ?",
                owner.rowMapper(), id).stream().findFirst();
    }

    /** 出站签名 / 入站鉴权（调用方）用：仅 ACTIVE（M0-04 §3.2 读取规则）。
     *  ORDER BY id DESC LIMIT 1（2026-09-18 补，代码评审 P2）：应用层约定「每 (属主,kind) 至多 1 条 ACTIVE」，
     *  但库级无唯一约束（PolarDB 5.7 不支持函数索引）——数据异常时取哪条至少要是确定的。 */
    public Optional<CredentialRow> findActive(CredentialOwner owner, String ownerId, String kind) {
        return jdbc.query("SELECT * FROM " + owner.table() + " WHERE " + owner.ownerPredicate() + " AND kind = ? AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1",
                owner.rowMapper(), ownerId, kind).stream().findFirst();
    }

    /**
     * 回调验签 / 入站鉴权用：ACTIVE + ROTATING 全部（未过 rotating_until 的 ROTATING 有效，
     * 过期惰性视为 RETIRED；逐个试、任一命中即通过，M0-04 §3.2）。
     */
    public List<CredentialRow> findVerifiable(CredentialOwner owner, String ownerId, String kind) {
        return jdbc.query("SELECT * FROM " + owner.table() + " WHERE " + owner.ownerPredicate() + " AND kind = ?"
                        + " AND (status = 'ACTIVE'"
                        + "      OR (status = 'ROTATING' AND (rotating_until IS NULL OR rotating_until > NOW())))"
                        + " ORDER BY status = 'ACTIVE' DESC, created_at DESC",
                owner.rowMapper(), ownerId, kind);
    }

    /**
     * ACTIVE 条数（语义化命名：原 countByStatus 会让 prepare 之类误用含过期行的口径——E2 缺陷根因）。
     * 需要「未过期 ROTATING 条数」请用 {@link #countLiveRotating(CredentialOwner, String, String)}。
     */
    public int countActive(CredentialOwner owner, String ownerId, String kind) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + owner.table() + " WHERE " + owner.ownerPredicate() + " AND kind = ? AND status = 'ACTIVE'", Integer.class, ownerId, kind);
        return n == null ? 0 : n;
    }

    /**
     * 未过期的 ROTATING 条数（E2，2026-09-11）：rotating_until 已过期的 ROTATING 在读取路径已惰性视为 RETIRED，
     * 不应再阻塞新轮换——原用 countByStatus 会把过期行也算进去，使 prepare 永久报「已有待激活的轮换凭证」。
     */
    public int countLiveRotating(CredentialOwner owner, String ownerId, String kind) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + owner.table() + " WHERE " + owner.ownerPredicate() + " AND kind = ? AND status = 'ROTATING'"
                + " AND (rotating_until IS NULL OR rotating_until > NOW())", Integer.class, ownerId, kind);
        return n == null ? 0 : n;
    }

    /**
     * 批量查「存在 ACTIVE 凭证」的属主 → kinds（E1，2026-09-11）：列表凭证角标用，一次 IN 查询避免 N+1。
     */
    public Map<String, Set<String>> findActiveKinds(CredentialOwner owner, List<String> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return Map.of();
        }
        String col = owner.column();
        String placeholders = String.join(",", Collections.nCopies(ownerIds.size(), "?"));
        Map<String, Set<String>> result = new HashMap<>();
        // v1.2：池形态需额外限定 owner_type（否则 CLIENT/INTERFACE 同值会串），且"属主列"为 owner_id
        String ownerFilter = owner.pooled()
                ? "owner_type = '" + owner.ownerType() + "' AND " + col + " IN (" + placeholders + ")"
                : col + " IN (" + placeholders + ")";
        jdbc.query("SELECT " + col + ", kind FROM " + owner.table() + " WHERE status = 'ACTIVE' AND "
                        + ownerFilter,
                // 显式声明为 RowCallbackHandler：否则与 ResultSetExtractor 重载二义
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.computeIfAbsent(rs.getString(col), k -> new HashSet<>())
                                .add(rs.getString("kind")),
                ownerIds.toArray());
        return result;
    }

    /** 是否存在明文未加密的凭证（单测断言用：库中不得出现明文） */
    public int countByCredentialText(CredentialOwner owner, String ownerId, String plaintext) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + owner.table() + " WHERE " + owner.ownerPredicate() + " AND credential = ?", Integer.class, ownerId, plaintext);
        return n == null ? 0 : n;
    }

    public int insert(CredentialOwner owner, CredentialRow row) {
        if (owner.pooled()) {
            // v1.2 凭证池形态：属主 = (owner_type, owner_id)，并带 label（发给谁/何时）
            return jdbc.update("INSERT INTO " + owner.table()
                            + " (owner_type, owner_id, label, kind, credential, status, rotating_until)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    owner.ownerType(), row.ownerId(), row.label(), row.kind(), row.credential(),
                    row.status(), row.rotatingUntil());
        }
        return jdbc.update("INSERT INTO " + owner.table() + " (" + owner.column()
                        + ", kind, credential, status, rotating_until) VALUES (?, ?, ?, ?, ?)",
                row.ownerId(), row.kind(), row.credential(), row.status(), row.rotatingUntil());
    }

    /**
     * 插入并回填自增 id（prepare 生成凭证需要返回真实 id 供前端「生成后立即激活」；
     * 原实现固定返回 id=-1，前端拿不到可操作目标——2026-09-12 修复）。
     */
    public long insertAndReturnId(CredentialOwner owner, CredentialRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        boolean pooled = owner.pooled();
        jdbc.update(con -> {
            String columns = pooled
                    ? "owner_type, owner_id, label, kind, credential, status, rotating_until"
                    : owner.column() + ", kind, credential, status, rotating_until";
            String values = pooled ? "(?, ?, ?, ?, ?, ?, ?)" : "(?, ?, ?, ?, ?)";
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO " + owner.table() + " (" + columns + ") VALUES " + values,
                    Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            if (pooled) {
                ps.setString(i++, owner.ownerType());
            }
            ps.setString(i++, row.ownerId());
            if (pooled) {
                ps.setString(i++, row.label());
            }
            ps.setString(i++, row.kind());
            ps.setString(i++, row.credential());
            ps.setString(i++, row.status());
            ps.setTimestamp(i, SqlTimes.ts(row.rotatingUntil()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("凭证插入未返回主键");
        }
        return key.longValue();
    }

    /** 状态流转：可同时更新 retired_at / rotating_until（传 null 表示不改） */
    public int updateStatus(CredentialOwner owner, long id, String status,
                            LocalDateTime retiredAt, LocalDateTime rotatingUntil) {
        return jdbc.update("UPDATE " + owner.table()
                        + " SET status = ?, retired_at = ?, rotating_until = ? WHERE id = ?",
                status, retiredAt, rotatingUntil, id);
    }

    /**
     * CAS 式状态流转（并发安全，M0-04 状态机约束兜底）：仅当当前状态为 fromStatus 时更新。
     * 返回受影响行数（0 = 已被并发变更），调用方据此拒绝/重试，防止同 (属主, kind) 双 ACTIVE / 双 ROTATING。
     */
    public int transitionStatus(CredentialOwner owner, long id, String fromStatus, String toStatus,
                                LocalDateTime retiredAt, LocalDateTime rotatingUntil) {
        return jdbc.update("UPDATE " + owner.table()
                        + " SET status = ?, retired_at = ?, rotating_until = ? WHERE id = ? AND status = ?",
                toStatus, retiredAt, rotatingUntil, id, fromStatus);
    }

    /** 物理删除凭证行（仅 RETIRED 可删，由 service 校验；ACTIVE/ROTATING 受保护） */
    public int delete(CredentialOwner owner, long id) {
        return jdbc.update("DELETE FROM " + owner.table() + " WHERE id = ?", id);
    }

    /** 按 (属主, kind) 批量置状态（reset：旧凭证全部 RETIRED） */
    public int retireAll(CredentialOwner owner, String ownerId, String kind) {
        return jdbc.update("UPDATE " + owner.table()
                        + " SET status = 'RETIRED', retired_at = NOW(), rotating_until = NULL WHERE "
                        + owner.ownerPredicate() + " AND kind = ? AND status <> 'RETIRED'",
                ownerId, kind);
    }

    /** 仅改备注（v1.2 凭证池）：限定池形态与 `owner_type`，避免误改应用凭证/跨池行 */
    public int updateLabel(CredentialOwner owner, long id, String label) {
        if (!owner.pooled()) {
            throw new IllegalStateException("仅入站凭证池支持备注：" + owner);
        }
        return jdbc.update("UPDATE " + owner.table() + " SET label = ? WHERE id = ? AND owner_type = '"
                + owner.ownerType() + "'", label, id);
    }

    /** 删属主时级联删其全部凭证（删调用方/删应用；审计表不删，见设计方案 §4.2） */
    public int deleteByOwner(CredentialOwner owner, String ownerId) {
        return jdbc.update("DELETE FROM " + owner.table() + " WHERE " + owner.ownerPredicate(), ownerId);
    }
}
