package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * app 表行（应用 = 供应商）。凭证不在此表，见 {@link CredentialRow}（app_credential 表）。
 * groupCount / ifaceCount 为列表场景的聚合计数（SQL 子查询提供，非表列）。
 */
public record AppRow(
        String appId, String name, String contact,
        String authAdapterId, String callbackAuthAdapterId, String defaultMessageAdapterId,
        String baseUrl, String ipWhitelist, String ipBlacklist,
        Integer qpsLimit, Long dailyQuota,
        String status, String desc,
        LocalDateTime createdAt, LocalDateTime updatedAt,
        long groupCount, long ifaceCount
) {

    /** 行映射（含 group_count / iface_count 聚合列，查询 SQL 需使用本类的 SELECT_SQL） */
    public static final RowMapper<AppRow> MAPPER = (rs, i) -> new AppRow(
            rs.getString("app_id"),
            rs.getString("name"),
            rs.getString("contact"),
            rs.getString("auth_adapter_id"),
            rs.getString("callback_auth_adapter_id"),
            rs.getString("default_message_adapter_id"),
            rs.getString("base_url"),
            rs.getString("ip_whitelist"),
            rs.getString("ip_blacklist"),
            (Integer) rs.getObject("qps_limit"),
            (Long) rs.getObject("daily_quota"),
            rs.getString("status"),
            rs.getString("desc"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime(),
            rs.getLong("group_count"),
            rs.getLong("iface_count")
    );
}
