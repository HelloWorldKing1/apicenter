package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.ClientAppRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * `client_app` 表数据访问（调用方 = 平台客户，2026-09-23 入站鉴权 B1）。
 *
 * <p>与 {@code AppRepository} 风格一致：列表硬上限 + 截断告警、无外键（引用完整性应用层保证）。
 * 凭证表（`client_credential`）的访问统一走 {@link CredentialRepository}（属主参数化），本类不碰凭证。
 */
@Repository
public class ClientAppRepository {

    private static final Logger log = LoggerFactory.getLogger(ClientAppRepository.class);

    /** 列表硬上限（与 AppRepository 同口径）：超限走搜索过滤，v1.1 再上分页 */
    private static final int LIST_LIMIT = 2000;

    private final JdbcTemplate jdbc;

    public ClientAppRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClientAppRow> findAll(String keyword, String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM client_app");
        List<Object> args = new ArrayList<>();
        List<String> where = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.add("(name LIKE ? OR client_id LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.add("status = ?");
            args.add(status);
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        sql.append(" ORDER BY created_at DESC LIMIT ").append(LIST_LIMIT);
        List<ClientAppRow> rows = jdbc.query(sql.toString(), ClientAppRow.MAPPER, args.toArray());
        if (rows.size() >= LIST_LIMIT) {
            log.warn("调用方列表命中硬上限 {}，结果可能被截断（请用 keyword / status 缩小范围）", LIST_LIMIT);
        }
        return rows;
    }

    public Optional<ClientAppRow> findById(String clientId) {
        return jdbc.query("SELECT * FROM client_app WHERE client_id = ?", ClientAppRow.MAPPER, clientId)
                .stream().findFirst();
    }

    public boolean existsById(String clientId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM client_app WHERE client_id = ?", Integer.class, clientId);
        return n != null && n > 0;
    }

    /** 闸门用：是否启用中（停用即拒 40107） */
    public boolean isEnabled(String clientId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM client_app WHERE client_id = ? AND status = 'ENABLED'", Integer.class, clientId);
        return n != null && n > 0;
    }

    public int insert(ClientAppRow row) {
        return jdbc.update("""
                INSERT INTO client_app (client_id, name, contact, auth_adapter_id,
                                        ip_whitelist, ip_blacklist, qps_limit, daily_quota, status, `desc`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.clientId(), row.name(), row.contact(), row.authAdapterId(),
                row.ipWhitelist(), row.ipBlacklist(), row.qpsLimit(), row.dailyQuota(),
                row.status(), row.desc());
    }

    public int update(ClientAppRow row) {
        return jdbc.update("""
                UPDATE client_app SET name = ?, contact = ?, auth_adapter_id = ?,
                       ip_whitelist = ?, ip_blacklist = ?, qps_limit = ?, daily_quota = ?, `desc` = ?
                WHERE client_id = ?
                """,
                row.name(), row.contact(), row.authAdapterId(),
                row.ipWhitelist(), row.ipBlacklist(), row.qpsLimit(), row.dailyQuota(),
                row.desc(), row.clientId());
    }

    public int updateStatus(String clientId, String status) {
        return jdbc.update("UPDATE client_app SET status = ? WHERE client_id = ?", status, clientId);
    }

    /**
     * 级联删除：调用方 + 其凭证（**审计表不删** —— 靠 principal_id/name 快照回溯，设计方案 §4.2）。
     *
     * <p>⚠️ v1.2（2026-09-24）：凭证归入**凭证池**后，属主由 `(owner_type='CLIENT', owner_id)` 表达 ——
     * 原实现按旧列 `client_id` 删，而新写入的池行 `client_id` 为 NULL ⇒ **会静默删不掉**（孤儿凭证行）。
     * 这条由 `InboundCredentialPoolIntegrationTest` 的清理钩子抓到，故此处改用属主谓词。
     */
    public void deleteCascade(String clientId) {
        jdbc.update("DELETE FROM client_credential WHERE owner_type = 'CLIENT' AND owner_id = ?", clientId);
        jdbc.update("DELETE FROM client_app WHERE client_id = ?", clientId);
    }

    /** 删除鉴权适配器时引用列置 NULL（schema.sql 删除策略：回退「未配置」→ fail-closed 40108） */
    public int clearAdapterRefs(String adapterId) {
        return jdbc.update("UPDATE client_app SET auth_adapter_id = NULL WHERE auth_adapter_id = ?", adapterId);
    }
}
