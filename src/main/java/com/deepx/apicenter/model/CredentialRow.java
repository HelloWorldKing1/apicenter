package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * app_credential 表行（M0-04 凭证轮换存储方案）：
 * OUTBOUND 出站签名凭证 / CALLBACK 回调验签凭证；
 * ACTIVE 当前使用 / ROTATING 轮换并存（过期惰性失效）/ RETIRED 已失效。
 * credential 为 AES-256-GCM 密文，仅运行时解密，管理面永不回显明文。
 */
public record CredentialRow(
        long id, String appId, String kind, String credential, String status,
        LocalDateTime activatedAt, LocalDateTime retiredAt,
        LocalDateTime rotatingUntil, LocalDateTime createdAt
) {

    public static final RowMapper<CredentialRow> MAPPER = (rs, i) -> new CredentialRow(
            rs.getLong("id"),
            rs.getString("app_id"),
            rs.getString("kind"),
            rs.getString("credential"),
            rs.getString("status"),
            rs.getTimestamp("activated_at").toLocalDateTime(),
            rs.getTimestamp("retired_at") == null ? null : rs.getTimestamp("retired_at").toLocalDateTime(),
            rs.getTimestamp("rotating_until") == null ? null : rs.getTimestamp("rotating_until").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime()
    );
}
