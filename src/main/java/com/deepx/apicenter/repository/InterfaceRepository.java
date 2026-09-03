package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.InterfaceRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * interface 主表 + 5 张子表数据访问。
 * 更新策略（M1 评审确认点 5）：全量替换——事务内子表 DELETE + 重建；
 * version 列做乐观锁（UPDATE ... WHERE version = ?），M5 版本化在自增时写 snapshot。
 * 删除策略（schema.sql 约定）：级联删 6 张配置子表（含 snapshot）；存在运行数据时仅允许下线（M2 起校验）。
 */
@Repository
public class InterfaceRepository {

    private static final String SELECT_SQL = """
            SELECT i.*, a.name AS app_name, g.name AS group_name
            FROM interface i
            JOIN app a ON a.app_id = i.app_id
            JOIN app_group g ON g.id = i.group_id
            """;

    private final JdbcTemplate jdbc;

    public InterfaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 主表 ----------

    public List<InterfaceRow> findAll(String appId, Long groupId, String ifType, String status, String keyword) {
        StringBuilder sql = new StringBuilder(SELECT_SQL + " WHERE 1 = 1 ");
        List<Object> args = new java.util.ArrayList<>();
        if (appId != null && !appId.isBlank()) {
            sql.append("AND i.app_id = ? ");
            args.add(appId);
        }
        if (groupId != null) {
            sql.append("AND i.group_id = ? ");
            args.add(groupId);
        }
        if (ifType != null && !ifType.isBlank()) {
            sql.append("AND i.if_type = ? ");
            args.add(ifType);
        }
        if (status != null && !status.isBlank()) {
            sql.append("AND i.status = ? ");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append("AND (i.name LIKE ? OR i.code LIKE ? OR i.path LIKE ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append("ORDER BY i.created_at DESC");
        return jdbc.query(sql.toString(), InterfaceRow.MAPPER, args.toArray());
    }

    public Optional<InterfaceRow> findById(long id) {
        return jdbc.query(SELECT_SQL + " WHERE i.id = ?", InterfaceRow.MAPPER, id).stream().findFirst();
    }

    /** 平台侧路径路由（执行面 M2 接入层用） */
    public Optional<InterfaceRow> findByPath(String path) {
        return jdbc.query(SELECT_SQL + " WHERE i.path = ?", InterfaceRow.MAPPER, path).stream().findFirst();
    }

    public boolean existsByCode(String code) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM interface WHERE code = ?", Integer.class, code);
        return n != null && n > 0;
    }

    public boolean existsByPath(String path) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM interface WHERE path = ?", Integer.class, path);
        return n != null && n > 0;
    }

    /** 更新场景的唯一性校验：排除自身后是否仍有同名 code / path */
    public int countByCode(String code, long excludeId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interface WHERE code = ? AND id <> ?", Integer.class, code, excludeId);
        return n == null ? 0 : n;
    }

    public int countByPath(String path, long excludeId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interface WHERE path = ? AND id <> ?", Integer.class, path, excludeId);
        return n == null ? 0 : n;
    }

    /** 插入主表并返回自增主键（子表重建时关联用） */
    public long insertAndGetId(InterfaceRow row) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO interface (code, name, if_type, method, path, protocol_in, protocol_out,
                                           app_id, group_id, upstream_path, callback_url, status,
                                           timeout_ms, max_retries, `desc`)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.code());
            ps.setString(2, row.name());
            ps.setString(3, row.ifType());
            ps.setString(4, row.method());
            ps.setString(5, row.path());
            ps.setString(6, row.protocolIn());
            ps.setString(7, row.protocolOut());
            ps.setString(8, row.appId());
            ps.setLong(9, row.groupId());
            ps.setString(10, row.upstreamPath());
            ps.setString(11, row.callbackUrl());
            ps.setString(12, row.status());
            ps.setInt(13, row.timeoutMs());
            ps.setInt(14, row.maxRetries());
            ps.setString(15, row.desc());
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1 : key.longValue();
    }

    /** 全量更新 + 乐观锁：version 匹配才更新并自增，返回受影响行数（0 = 冲突） */
    public int updateWithVersion(InterfaceRow row) {
        return jdbc.update("""
                UPDATE interface SET code = ?, name = ?, if_type = ?, method = ?, path = ?,
                       protocol_in = ?, protocol_out = ?, app_id = ?, group_id = ?,
                       upstream_path = ?, callback_url = ?, status = ?, version = version + 1,
                       timeout_ms = ?, max_retries = ?, `desc` = ?
                WHERE id = ? AND version = ?
                """,
                row.code(), row.name(), row.ifType(), row.method(), row.path(),
                row.protocolIn(), row.protocolOut(), row.appId(), row.groupId(),
                row.upstreamPath(), row.callbackUrl(), row.status(),
                row.timeoutMs(), row.maxRetries(), row.desc(),
                row.id(), row.version());
    }

    public int updateStatus(long id, String status) {
        return jdbc.update("UPDATE interface SET status = ? WHERE id = ?", status, id);
    }

    /** 级联删除：6 张配置子表 + 主表（前提：service 已校验无运行数据） */
    public void deleteCascade(long id) {
        jdbc.update("DELETE FROM interface_snapshot WHERE interface_id = ?", id);
        jdbc.update("DELETE FROM interface_param WHERE interface_id = ?", id);
        jdbc.update("DELETE FROM interface_body WHERE interface_id = ?", id);
        jdbc.update("DELETE FROM interface_field_mapping WHERE interface_id = ?", id);
        jdbc.update("DELETE FROM interface_field_def WHERE interface_id = ?", id);
        jdbc.update("DELETE FROM interface_adapter_binding WHERE interface_id = ?", id);
        jdbc.update("DELETE FROM interface WHERE id = ?", id);
    }

    /** 删除全部子表（全量更新的重建前置，事务内由 service 调用） */
    public void deleteChildren(long interfaceId) {
        jdbc.update("DELETE FROM interface_param WHERE interface_id = ?", interfaceId);
        jdbc.update("DELETE FROM interface_body WHERE interface_id = ?", interfaceId);
        jdbc.update("DELETE FROM interface_field_mapping WHERE interface_id = ?", interfaceId);
        jdbc.update("DELETE FROM interface_field_def WHERE interface_id = ?", interfaceId);
        jdbc.update("DELETE FROM interface_adapter_binding WHERE interface_id = ?", interfaceId);
    }

    // ---------- 子表查询 ----------

    public List<InterfaceRow.ParamRow> findParams(long interfaceId) {
        return jdbc.query("SELECT * FROM interface_param WHERE interface_id = ? ORDER BY sort_order, id",
                InterfaceRow.ParamRow.MAPPER, interfaceId);
    }

    public List<InterfaceRow.BodyRow> findBodies(long interfaceId) {
        return jdbc.query("SELECT * FROM interface_body WHERE interface_id = ?", InterfaceRow.BodyRow.MAPPER, interfaceId);
    }

    public List<InterfaceRow.MappingRow> findMappings(long interfaceId) {
        return jdbc.query("SELECT * FROM interface_field_mapping WHERE interface_id = ? ORDER BY sort_order, id",
                InterfaceRow.MappingRow.MAPPER, interfaceId);
    }

    public List<InterfaceRow.FieldDefRow> findFieldDefs(long interfaceId) {
        return jdbc.query("SELECT * FROM interface_field_def WHERE interface_id = ? ORDER BY sort_order, id",
                InterfaceRow.FieldDefRow.MAPPER, interfaceId);
    }

    public List<InterfaceRow.BindingRow> findBindings(long interfaceId) {
        return jdbc.query("SELECT * FROM interface_adapter_binding WHERE interface_id = ?",
                InterfaceRow.BindingRow.MAPPER, interfaceId);
    }

    // ---------- 子表写入（全量重建） ----------

    public void insertParams(long interfaceId, List<InterfaceRow.ParamRow> rows) {
        for (InterfaceRow.ParamRow r : rows) {
            jdbc.update("""
                    INSERT INTO interface_param (interface_id, side, name, type, required, sample, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, interfaceId, r.side(), r.name(), r.type(), r.required(), r.sample(), r.sortOrder());
        }
    }

    public void insertBodies(long interfaceId, List<InterfaceRow.BodyRow> rows) {
        for (InterfaceRow.BodyRow r : rows) {
            jdbc.update("""
                    INSERT INTO interface_body (interface_id, side, body_type, raw, form)
                    VALUES (?, ?, ?, ?, ?)
                    """, interfaceId, r.side(), r.bodyType(), r.raw(), r.form());
        }
    }

    public void insertMappings(long interfaceId, List<InterfaceRow.MappingRow> rows) {
        for (InterfaceRow.MappingRow r : rows) {
            jdbc.update("""
                    INSERT INTO interface_field_mapping (interface_id, source, op, target, param, null_strategy, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, interfaceId, r.source(), r.op(), r.target(), r.param(), r.nullStrategy(), r.sortOrder());
        }
    }

    public void insertFieldDefs(long interfaceId, List<InterfaceRow.FieldDefRow> rows) {
        for (InterfaceRow.FieldDefRow r : rows) {
            jdbc.update("""
                    INSERT INTO interface_field_def (interface_id, kind, name, type, `desc`, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, interfaceId, r.kind(), r.name(), r.type(), r.desc(), r.sortOrder());
        }
    }

    public void insertBindings(long interfaceId, List<InterfaceRow.BindingRow> rows) {
        for (InterfaceRow.BindingRow r : rows) {
            jdbc.update("""
                    INSERT INTO interface_adapter_binding (interface_id, `role`, adapter_id, version)
                    VALUES (?, ?, ?, ?)
                    """, interfaceId, r.role(), r.adapterId(), r.version());
        }
    }

    /** 删除适配器时绑定引用置 NULL（schema.sql 删除策略） */
    public int clearBindingRefs(String adapterId) {
        return jdbc.update("UPDATE interface_adapter_binding SET adapter_id = NULL WHERE adapter_id = ?", adapterId);
    }
}
