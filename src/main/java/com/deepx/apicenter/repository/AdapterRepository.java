package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.AdapterRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * adapter 表数据访问（适配器定义）。
 * 约束（M0-01 D6，M5 灰度放宽为 impl + version 维度）：同 impl 同 version 至多 1 条 enabled=1——
 * 灰度需同 impl 多版本共存且均启用（D-M5-2 矩阵 #3），不同 version 不受限。
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

    /** 同 (impl, version) 启用的记录数（M5 D6 灰度放宽：版本维度唯一；同版本重复实例仍拒绝） */
    public int countEnabledByImplVersion(String impl, String version, String excludeId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM adapter WHERE impl = ? AND version = ? AND enabled = 1 AND id <> ?",
                Integer.class, impl, version, excludeId);
        return n == null ? 0 : n;
    }

    /** 灰度矩阵 #3：同 impl + 指定 version 且启用的行（D-M5-2 解析用） */
    public Optional<AdapterRow> findByImplAndVersionEnabled(String impl, String version) {
        return jdbc.query("SELECT * FROM adapter WHERE impl = ? AND version = ? AND enabled = 1",
                AdapterRow.MAPPER, impl, version).stream().findFirst();
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
