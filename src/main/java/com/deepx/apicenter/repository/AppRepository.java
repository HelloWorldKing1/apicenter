package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.AppRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * app 表数据访问（应用 = 供应商）。删除策略（schema.sql 约定）：
 * 删应用 → 级联删其分组与凭证；存在接口时禁止删除（由 service 校验）。
 */
@Repository
public class AppRepository {

    /** 列表查询（含分组数 / 接口数聚合列，与 AppRow.MAPPER 对齐） */
    static final String SELECT_SQL = """
            SELECT a.*,
                   (SELECT COUNT(*) FROM app_group g WHERE g.app_id = a.app_id) AS group_count,
                   (SELECT COUNT(*) FROM interface i WHERE i.app_id = a.app_id) AS iface_count
            FROM app a
            """;

    private final JdbcTemplate jdbc;

    public AppRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AppRow> findAll(String keyword, String status) {
        StringBuilder sql = new StringBuilder(SELECT_SQL);
        java.util.List<Object> args = new java.util.ArrayList<>();
        java.util.List<String> where = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.add("(a.name LIKE ? OR a.app_id LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.add("a.status = ?");
            args.add(status);
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        sql.append(" ORDER BY a.created_at DESC");
        return jdbc.query(sql.toString(), AppRow.MAPPER, args.toArray());
    }

    public Optional<AppRow> findById(String appId) {
        return jdbc.query(SELECT_SQL + " WHERE a.app_id = ?", AppRow.MAPPER, appId).stream().findFirst();
    }

    public boolean existsById(String appId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM app WHERE app_id = ?", Integer.class, appId);
        return n != null && n > 0;
    }

    public int insert(AppRow row) {
        return jdbc.update("""
                INSERT INTO app (app_id, name, contact, auth_adapter_id, callback_auth_adapter_id,
                                 default_message_adapter_id, base_url, ip_whitelist, ip_blacklist,
                                 qps_limit, daily_quota, status, `desc`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.appId(), row.name(), row.contact(),
                row.authAdapterId(), row.callbackAuthAdapterId(), row.defaultMessageAdapterId(),
                row.baseUrl(), row.ipWhitelist(), row.ipBlacklist(),
                row.qpsLimit(), row.dailyQuota(), row.status(), row.desc());
    }

    public int update(AppRow row) {
        return jdbc.update("""
                UPDATE app SET name = ?, contact = ?, auth_adapter_id = ?, callback_auth_adapter_id = ?,
                       default_message_adapter_id = ?, base_url = ?, ip_whitelist = ?, ip_blacklist = ?,
                       qps_limit = ?, daily_quota = ?, `desc` = ?
                WHERE app_id = ?
                """,
                row.name(), row.contact(),
                row.authAdapterId(), row.callbackAuthAdapterId(), row.defaultMessageAdapterId(),
                row.baseUrl(), row.ipWhitelist(), row.ipBlacklist(),
                row.qpsLimit(), row.dailyQuota(), row.desc(), row.appId());
    }

    public int updateStatus(String appId, String status) {
        return jdbc.update("UPDATE app SET status = ? WHERE app_id = ?", status, appId);
    }

    /** 是否存在（生命周期）指定状态的应用——停用即拒的网关校验钩子用 */
    public boolean isEnabled(String appId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app WHERE app_id = ? AND status = 'ENABLED'", Integer.class, appId);
        return n != null && n > 0;
    }

    public int countInterfaces(String appId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM interface WHERE app_id = ?", Integer.class, appId);
        return n == null ? 0 : n;
    }

    /** 级联删除：应用 + 其分组 + 其凭证（前提：service 已校验无接口） */
    public void deleteCascade(String appId) {
        jdbc.update("DELETE FROM app_group WHERE app_id = ?", appId);
        jdbc.update("DELETE FROM app_credential WHERE app_id = ?", appId);
        jdbc.update("DELETE FROM app WHERE app_id = ?", appId);
    }

    /** 删除适配器时引用列置 NULL（schema.sql 删除策略） */
    public int clearAdapterRefs(String adapterId) {
        int n = 0;
        n += jdbc.update("UPDATE app SET auth_adapter_id = NULL WHERE auth_adapter_id = ?", adapterId);
        n += jdbc.update("UPDATE app SET callback_auth_adapter_id = NULL WHERE callback_auth_adapter_id = ?", adapterId);
        n += jdbc.update("UPDATE app SET default_message_adapter_id = NULL WHERE default_message_adapter_id = ?", adapterId);
        return n;
    }
}
