package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.AdapterRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * adapter 表数据访问（适配器定义）。
 * 约束（D6' 定稿，2026-09-08）：adapter.name 全表唯一（id 为唯一键与绑定键）；
 * 同 (impl, version) 允许多条启用并存（不做灰度路由，多实例靠 id+name 区分；
 * 并行首选同 impl 不同 version）；binding.version 仅记录/留痕不参与运行时定位。
 * 删除策略：app 三列 + binding.adapter_id 引用置 NULL（回退「无鉴权 / 平台默认」）。
 */
@Repository
public class AdapterRepository {

    private final JdbcTemplate jdbc;

    public AdapterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AdapterRow> findAll(String type) {
        if (type == null || type.isBlank()) {
            return jdbc.query("SELECT * FROM adapter ORDER BY id", AdapterRow.MAPPER);
        }
        return jdbc.query("SELECT * FROM adapter WHERE type = ? ORDER BY id", AdapterRow.MAPPER, type);
    }

    public Optional<AdapterRow> findById(String id) {
        return jdbc.query("SELECT * FROM adapter WHERE id = ?", AdapterRow.MAPPER, id).stream().findFirst();
    }

    /** 同 impl 启用的记录数（校验「同 impl 至多 1 条 enabled」，保留：停用前查用） */
    public int countEnabledByImpl(String impl, String excludeId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM adapter WHERE impl = ? AND enabled = 1 AND id <> ?", Integer.class, impl, excludeId);
        return n == null ? 0 : n;
    }

    /** adapter.name 全表唯一（D6' 语义：实例区分靠 id + name；同 (impl, version) 允许多条启用并存，不做灰度路由） */
    public int countByName(String name, String excludeId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM adapter WHERE name = ? AND id <> ?",
                Integer.class, name, excludeId == null ? "" : excludeId);
        return n == null ? 0 : n;
    }

    public boolean existsById(String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM adapter WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    public int insert(AdapterRow row) {
        return jdbc.update("""
                INSERT INTO adapter (id, name, type, impl, enabled, version, params)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.name(), row.type(), row.impl(), row.enabled(), row.version(), row.params());
    }

    public int update(AdapterRow row) {
        return jdbc.update("""
                UPDATE adapter SET name = ?, type = ?, impl = ?, enabled = ?, version = ?, params = ?
                WHERE id = ?
                """, row.name(), row.type(), row.impl(), row.enabled(), row.version(), row.params(), row.id());
    }

    public int updateEnabled(String id, boolean enabled) {
        return jdbc.update("UPDATE adapter SET enabled = ? WHERE id = ?", enabled, id);
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM adapter WHERE id = ?", id);
    }
}
