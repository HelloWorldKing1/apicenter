package com.deepx.apicenter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * interface_snapshot 表数据访问（M5 D-M5-1 接口配置快照）。
 * 快照写入时机：创建（v1.0）+ 每次成功配置更新 / 回滚（version +0.1）；status 流转（发布 / 下线）不生成版本。
 * 表结构：interface_id + version 唯一键（uk_snapshot）；config_json 完整可重建；
 * change_detail 为结构化变更详情 JSON（版本历史「变更详情」渲染源，可空 = 历史/回滚/创建/复制）。
 */
@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    /** 版本列表行（倒序分页；含是否带结构化变更详情——config_json/change_detail 详情单独查） */
    public record SnapshotItem(long id, long interfaceId, BigDecimal version, String changeNote,
                               boolean hasDetail, LocalDateTime createdAt) {
        public static final RowMapper<SnapshotItem> MAPPER = (rs, i) -> new SnapshotItem(
                rs.getLong("id"), rs.getLong("interface_id"), rs.getBigDecimal("version"),
                rs.getString("change_note"), rs.getBoolean("has_detail"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    /** 快照详情（回滚来源 + 结构化变更详情）：含 config_json 与 change_detail */
    public record SnapshotDetail(long id, long interfaceId, BigDecimal version,
                                 String configJson, String changeDetail,
                                 String changeNote, LocalDateTime createdAt) {
        public static final RowMapper<SnapshotDetail> MAPPER = (rs, i) -> new SnapshotDetail(
                rs.getLong("id"), rs.getLong("interface_id"), rs.getBigDecimal("version"),
                rs.getString("config_json"), rs.getString("change_detail"), rs.getString("change_note"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    public void insert(long interfaceId, BigDecimal version, String configJson,
                       String changeNote, String changeDetail) {
        jdbc.update("""
                INSERT INTO interface_snapshot (interface_id, version, config_json, change_note, change_detail)
                VALUES (?, ?, ?, ?, ?)
                """, interfaceId, version, configJson, changeNote, changeDetail);
    }

    public Optional<SnapshotDetail> find(long interfaceId, BigDecimal version) {
        return jdbc.query("SELECT * FROM interface_snapshot WHERE interface_id = ? AND version = ?",
                SnapshotDetail.MAPPER, interfaceId, version).stream().findFirst();
    }

    public List<SnapshotItem> listPage(long interfaceId, int offset, int limit) {
        return jdbc.query("""
                SELECT id, interface_id, version, change_note, created_at,
                       (change_detail IS NOT NULL) AS has_detail
                FROM interface_snapshot
                WHERE interface_id = ? ORDER BY version DESC LIMIT ? OFFSET ?
                """, SnapshotItem.MAPPER, interfaceId, limit, offset);
    }

    public long count(long interfaceId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interface_snapshot WHERE interface_id = ?", Long.class, interfaceId);
        return n == null ? 0 : n;
    }

    public Optional<BigDecimal> maxVersion(long interfaceId) {
        return jdbc.query("SELECT MAX(version) FROM interface_snapshot WHERE interface_id = ?",
                        (rs, i) -> rs.getBigDecimal(1), interfaceId)
                .stream().findFirst();
    }
}
