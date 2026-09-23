package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * 凭证表行（M0-04 凭证轮换存储方案），**两类属主共用**（2026-09-23 起）：
 * <ul>
 *   <li>{@code APP} → `app_credential`：`OUTBOUND` 出站签名 / `CALLBACK` 回调验签；</li>
 *   <li>{@code CLIENT} → `client_credential`：入站鉴权（`API_KEY` / `HMAC_SECRET` / `BEARER_TOKEN` / `BASIC`）。</li>
 * </ul>
 *
 * <p>状态机：`ACTIVE` 当前使用 / `ROTATING` 轮换并存（过期惰性失效）/ `RETIRED` 已失效。
 * `credential` 为 AES-256-GCM 密文，仅运行时解密，管理面永不回显明文。
 *
 * <p>⚠️ 字段名是 **`ownerId`**（不是 `appId`）：它是"属主标识"，取值随 {@code CredentialOwner} 为 `app_id` 或 `client_id`
 * ——刻意不叫 `appId`，避免调用方凭证的语义被误读成"应用的"（设计方案 D-CA-1 / §7.1）。
 */
public record CredentialRow(
        long id, String ownerId, String kind, String credential, String status,
        LocalDateTime activatedAt, LocalDateTime retiredAt,
        LocalDateTime rotatingUntil, LocalDateTime createdAt,
        /** v1.2：池形态的人工备注（「发给谁 / 何时」）；应用形态恒 null（`app_credential` 无此列） */
        String label
) {

    /** 按属主列名映射（`app_id`；**无 label 列** —— 应用形态）；列名来自 {@code CredentialOwner} 常量，非用户输入 */
    public static RowMapper<CredentialRow> mapperFor(String ownerColumn) {
        return (rs, i) -> new CredentialRow(
                rs.getLong("id"),
                rs.getString(ownerColumn),
                rs.getString("kind"),
                rs.getString("credential"),
                rs.getString("status"),
                rs.getTimestamp("activated_at").toLocalDateTime(),
                rs.getTimestamp("retired_at") == null ? null : rs.getTimestamp("retired_at").toLocalDateTime(),
                rs.getTimestamp("rotating_until") == null ? null : rs.getTimestamp("rotating_until").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(),
                null
        );
    }

    /** 应用形态映射（`app_credential`：属主列 `app_id`、**无 `label` 列**） */
    public static CredentialRow ofApp(java.sql.ResultSet rs) throws java.sql.SQLException {
        return mapperFor("app_id").mapRow(rs, 0);
    }

    /**
     * 凭证池形态映射（`client_credential`：属主列 `owner_id` + **`label`**，v1.2）。
     * `PLATFORM` 属主的 `owner_id` 为 NULL（平台共享池）。
     */
    public static CredentialRow ofPooled(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CredentialRow(
                rs.getLong("id"),
                rs.getString("owner_id"),
                rs.getString("kind"),
                rs.getString("credential"),
                rs.getString("status"),
                rs.getTimestamp("activated_at").toLocalDateTime(),
                rs.getTimestamp("retired_at") == null ? null : rs.getTimestamp("retired_at").toLocalDateTime(),
                rs.getTimestamp("rotating_until") == null ? null : rs.getTimestamp("rotating_until").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("label")
        );
    }

    /** 默认（应用凭证）映射：`app_id` */
    public static final RowMapper<CredentialRow> MAPPER = mapperFor("app_id");
}
