package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * app_group 表行（应用下的组织单元，纯归类/展示用）。ifaceCount 为列表场景的聚合计数。
 */
public record AppGroupRow(
        long id, String appId, String name, int sortOrder,
        LocalDateTime createdAt, long ifaceCount
) {

    public static final RowMapper<AppGroupRow> MAPPER = (rs, i) -> new AppGroupRow(
            rs.getLong("id"),
            rs.getString("app_id"),
            rs.getString("name"),
            rs.getInt("sort_order"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getLong("iface_count")
    );
}
