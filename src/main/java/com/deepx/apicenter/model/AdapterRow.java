package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * adapter 表行（适配器定义：auth / protocol / message 三类，无状态、配置驱动）。
 * params 为 JSON 字符串（按 impl 元数据 schema）；凭证类参数不落此表，统一存 app_credential。
 */
public record AdapterRow(
        String id, String name, String type, String impl, boolean enabled,
        String version, String params, LocalDateTime createdAt, LocalDateTime updatedAt
) {

    public static final RowMapper<AdapterRow> MAPPER = (rs, i) -> new AdapterRow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("type"),
            rs.getString("impl"),
            rs.getBoolean("enabled"),
            rs.getString("version"),
            rs.getString("params"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );
}
