package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * interface 表行（接口定义，主表）与 5 张子表行（param / body / field_mapping / field_def / binding）。
 * 子表行嵌套声明于此（一表一 record，聚合在所属聚合根文件内）。
 */
public record InterfaceRow(
        long id, String code, String name, String ifType, String method, String path,
        String protocolIn, String protocolOut, String appId, long groupId,
        String upstreamPath, String callbackUrl, String status, int version,
        int timeoutMs, int maxRetries, String desc,
        LocalDateTime createdAt, LocalDateTime updatedAt,
        String appName, String groupName
) {

    public static final RowMapper<InterfaceRow> MAPPER = (rs, i) -> new InterfaceRow(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("if_type"),
            rs.getString("method"),
            rs.getString("path"),
            rs.getString("protocol_in"),
            rs.getString("protocol_out"),
            rs.getString("app_id"),
            rs.getLong("group_id"),
            rs.getString("upstream_path"),
            rs.getString("callback_url"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getInt("timeout_ms"),
            rs.getInt("max_retries"),
            rs.getString("desc"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime(),
            rs.getString("app_name"),
            rs.getString("group_name")
    );

    /** 请求参数（interface_param）：IN 入站侧 / OUT 出站侧（入站回调的 OUT = 送达报文） */
    public record ParamRow(long id, String side, String name, String type,
                           boolean required, String sample, int sortOrder) {
        public static final RowMapper<ParamRow> MAPPER = (rs, i) -> new ParamRow(
                rs.getLong("id"), rs.getString("side"), rs.getString("name"), rs.getString("type"),
                rs.getBoolean("required"), rs.getString("sample"), rs.getInt("sort_order"));
    }

    /** 请求体（interface_body）：每侧一个 */
    public record BodyRow(long id, String side, String bodyType, String raw, String form) {
        public static final RowMapper<BodyRow> MAPPER = (rs, i) -> new BodyRow(
                rs.getLong("id"), rs.getString("side"), rs.getString("body_type"),
                rs.getString("raw"), rs.getString("form"));
    }

    /** 字段映射（interface_field_mapping）：运行时规则，入站 → 出站 */
    public record MappingRow(long id, String source, String op, String target,
                             String param, String nullStrategy, int sortOrder) {
        public static final RowMapper<MappingRow> MAPPER = (rs, i) -> new MappingRow(
                rs.getLong("id"), rs.getString("source"), rs.getString("op"), rs.getString("target"),
                rs.getString("param"), rs.getString("null_strategy"), rs.getInt("sort_order"));
    }

    /** 响应 / ack 字段（interface_field_def）：RESP 出站响应 / ACK ack 回执 */
    public record FieldDefRow(long id, String kind, String name, String type,
                              String desc, int sortOrder) {
        public static final RowMapper<FieldDefRow> MAPPER = (rs, i) -> new FieldDefRow(
                rs.getLong("id"), rs.getString("kind"), rs.getString("name"), rs.getString("type"),
                rs.getString("desc"), rs.getInt("sort_order"));
    }

    /** 接口-适配器绑定（interface_adapter_binding）：MESSAGE / AUTH / CALLBACK_AUTH 三角色 */
    public record BindingRow(long id, String role, String adapterId, String version) {
        public static final RowMapper<BindingRow> MAPPER = (rs, i) -> new BindingRow(
                rs.getLong("id"), rs.getString("role"),
                rs.getString("adapter_id"), rs.getString("version"));
    }
}
