package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * `client_app` 表行（**调用方** = 平台客户 / 接入方，2026-09-23 入站鉴权 B1）。
 *
 * <p>与 {@link AppRow}（应用 = 供应商）**方向相反**：`app.auth_adapter_id` 管「平台作为调用方 → 供应商」的出站签名；
 * 本表的 `authAdapterId` 管「**调用方 → 平台**」的入站鉴权。二者主体相反，不要混用（设计方案 D-CA-1）。
 *
 * <p>凭证不在本表，见 {@link CredentialRow}（`client_credential`，`CredentialOwner.CLIENT`）。
 * 列表的「凭证角标」由 service 用 `findActiveKinds(CLIENT, ids)` 一次 IN 查询补齐（非本表列）。
 */
public record ClientAppRow(
        Long id, String clientId, String name, String contact,
        String authAdapterId,
        String ipWhitelist, String ipBlacklist,
        Integer qpsLimit, Long dailyQuota,
        String status, String desc,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {

    public static final RowMapper<ClientAppRow> MAPPER = (rs, i) -> new ClientAppRow(
            (Long) rs.getObject("id"),
            rs.getString("client_id"),
            rs.getString("name"),
            rs.getString("contact"),
            rs.getString("auth_adapter_id"),
            rs.getString("ip_whitelist"),
            rs.getString("ip_blacklist"),
            (Integer) rs.getObject("qps_limit"),
            (Long) rs.getObject("daily_quota"),
            rs.getString("status"),
            rs.getString("desc"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );
}
