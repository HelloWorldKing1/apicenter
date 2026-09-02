package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.AppGroupRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * app_group 表数据访问（分组：纯归类/展示，不承载配置）。
 * 删除策略：组下有接口禁止删除（由 service 校验，M1 评审确认点 3）。
 */
@Repository
public class GroupRepository {

    private static final String SELECT_SQL = """
            SELECT g.*,
                   (SELECT COUNT(*) FROM interface i WHERE i.group_id = g.id) AS iface_count
            FROM app_group g
            """;

    private final JdbcTemplate jdbc;

    public GroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 两级视图：按应用组织的分组列表（可带 appId 过滤） */
    public List<AppGroupRow> findAll(String appId) {
        if (appId == null || appId.isBlank()) {
            return jdbc.query(SELECT_SQL + " ORDER BY g.app_id, g.sort_order", AppGroupRow.MAPPER);
        }
        return jdbc.query(SELECT_SQL + " WHERE g.app_id = ? ORDER BY g.sort_order", AppGroupRow.MAPPER, appId);
    }

    public Optional<AppGroupRow> findById(long id) {
        return jdbc.query(SELECT_SQL + " WHERE g.id = ?", AppGroupRow.MAPPER, id).stream().findFirst();
    }

    public boolean existsByName(String appId, String name) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_group WHERE app_id = ? AND name = ?", Integer.class, appId, name);
        return n != null && n > 0;
    }

    public int insert(AppGroupRow row) {
        return jdbc.update("INSERT INTO app_group (app_id, name, sort_order) VALUES (?, ?, ?)",
                row.appId(), row.name(), row.sortOrder());
    }

    public int update(long id, String name, int sortOrder) {
        return jdbc.update("UPDATE app_group SET name = ?, sort_order = ? WHERE id = ?", name, sortOrder, id);
    }

    public int countInterfaces(long groupId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM interface WHERE group_id = ?", Integer.class, groupId);
        return n == null ? 0 : n;
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM app_group WHERE id = ?", id);
    }
}
