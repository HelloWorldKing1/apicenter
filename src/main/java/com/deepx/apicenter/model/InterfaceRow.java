package com.deepx.apicenter.model;

import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * interface 表行（接口定义，主表）与 5 张子表行（param / body / field_mapping / field_def / binding）。
 * 子表行嵌套声明于此（一表一 record，聚合在所属聚合根文件内）。
 */
public record InterfaceRow(
        long id, String code, String name, String ifType, String method, String path,
        String protocolIn, String protocolOut, String appId, long groupId,
        String upstreamPath, String callbackUrl, String status, BigDecimal version,
        int timeoutMs, int maxRetries, String desc,
        LocalDateTime createdAt, LocalDateTime updatedAt,
        String appName, String groupName
) {

    /** 便捷构造：整值版本（历史/测试直构）转 BigDecimal（v{n}.0） */
    public InterfaceRow(
            long id, String code, String name, String ifType, String method, String path,
            String protocolIn, String protocolOut, String appId, Long groupId,
            String upstreamPath, String callbackUrl, String status, int version,
            int timeoutMs, int maxRetries, String desc,
            LocalDateTime createdAt, LocalDateTime updatedAt,
            String appName, String groupName) {
        this(id, code, name, ifType, method, path, protocolIn, protocolOut, appId, groupId,
                upstreamPath, callbackUrl, status, BigDecimal.valueOf(version),
                timeoutMs, maxRetries, desc, createdAt, updatedAt, appName, groupName);
    }

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
            rs.getBigDecimal("version"),
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

    /** 前置步骤写入行（interface_step 本表字段；无 join 展示列） */
    public record StepRow(long id, long interfaceId, int seq, String stepCode,
                          long targetInterfaceId, String failurePolicy, boolean enabled) {
    }

    /**
     * 前置步骤（interface_step，第 7 张配置子表；见《前置接口编排设计方案》）：
     * 宿主接口（仅 OUTBOUND）在自身链的 MAPPING 前，按 `seq` 串行复用目标接口作为前置。
     * 目标展示字段（targetCode/targetName/targetStatus/targetIfType）来自 join，仅供管理面展示与校验。
     */
    public record StepView(long id, long interfaceId, int seq, String stepCode,
                           long targetInterfaceId, String failurePolicy, boolean enabled,
                           String targetCode, String targetName, String targetStatus, String targetIfType) {
        public static final RowMapper<StepView> MAPPER = (rs, i) -> new StepView(
                rs.getLong("id"), rs.getLong("interface_id"), rs.getInt("seq"), rs.getString("step_code"),
                rs.getLong("target_interface_id"), rs.getString("failure_policy"), rs.getBoolean("enabled"),
                rs.getString("target_code"), rs.getString("target_name"),
                rs.getString("target_status"), rs.getString("target_if_type"));
    }

    /** 前置步骤引用者（删除守卫提示用）：宿主 code + 步骤名 */
    public record StepRefView(long hostInterfaceId, String hostCode, String stepCode) {
    }
}
