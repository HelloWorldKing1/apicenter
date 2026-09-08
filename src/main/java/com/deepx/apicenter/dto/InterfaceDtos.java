package com.deepx.apicenter.dto;

import com.deepx.apicenter.model.InterfaceRow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 接口管理 DTO：请求为完整接口定义模型（主表 + 5 张子表），
 * 更新为全量替换语义 + version 乐观锁（M1 评审确认点 5）。
 */
public final class InterfaceDtos {

    private InterfaceDtos() {
    }

    public record ParamDto(String side, String name, String type,
                           Boolean required, String sample, Integer sortOrder) {
    }

    public record BodyDto(String side, String bodyType, String raw, String form) {
    }

    /** 字段映射规则（M0-02 校验规则：target 必填、非 default 需 source、参数化操作需 param） */
    public record MappingDto(String source, String op, String target, String param,
                             String nullStrategy, Integer sortOrder) {
    }

    /** 响应 / ack 字段：RESP（仅出站）/ ACK（仅入站） */
    public record FieldDefDto(String kind, String name, String type, String desc, Integer sortOrder) {
    }

    /** 适配器绑定：MESSAGE / AUTH（仅出站）/ CALLBACK_AUTH（仅入站）；adapterId 空 = 继承应用默认 */
    public record BindingDto(String role, String adapterId, String version) {
    }

    /**
     * 接口复制请求（方案 B，2026-09-07 拍板）：code/path 必填（全局唯一）；name/desc/upstreamPath/callbackUrl
     * 可空 = 沿用源；归属固定 = 源应用/源分组（不支持跨应用）；产物 = DRAFT v1.0。
     */
    public record CopyRequest(
            @NotBlank(message = "新接口标识不能为空") String code,
            @NotBlank(message = "平台侧路径不能为空") String path,
            String name,
            String upstreamPath,
            String callbackUrl,
            String desc
    ) {
    }

    /**
     * 回滚请求（M5 D-M5-1）：targetVersion 目标快照、currentVersion 乐观锁、operator / reason 审计拼入 change_note。
     * H2 修复：operator / reason 限长（change_note 列 VARCHAR(255)，拼接后仍须 < 250，防超长直插 500）。
     */
    public record RollbackRequest(
            @NotNull(message = "目标版本不能为空") BigDecimal targetVersion,
            @Size(max = 100, message = "操作人最多 100 字") String operator,
            @Size(max = 100, message = "回滚依据最多 100 字") String reason,
            @NotNull(message = "当前版本不能为空（乐观锁）") BigDecimal currentVersion
    ) {
    }

    public record InterfaceRequest(
            @NotBlank(message = "接口标识不能为空") String code,
            @NotBlank(message = "接口名称不能为空") String name,
            @NotBlank(message = "接口类型不能为空") String ifType,
            @NotBlank(message = "HTTP 方法不能为空") String method,
            @NotBlank(message = "平台侧路径不能为空") String path,
            String protocolIn,
            String protocolOut,
            @NotBlank(message = "归属应用不能为空") String appId,
            @NotNull(message = "归属分组不能为空") Long groupId,
            String upstreamPath,
            String callbackUrl,
            String status,
            Integer timeoutMs,
            Integer maxRetries,
            String desc,
            @NotNull(message = "版本号不能为空(乐观锁)") BigDecimal version,
            List<ParamDto> params,
            List<BodyDto> bodies,
            List<MappingDto> mappings,
            List<FieldDefDto> fieldDefs,
            List<BindingDto> bindings
    ) {
        /** 兼容构造：历史/测试以整值传版本（如 1）时自动转 BigDecimal */
        public InterfaceRequest(
                String code, String name, String ifType, String method, String path,
                String protocolIn, String protocolOut, String appId, Long groupId,
                String upstreamPath, String callbackUrl, String status,
                Integer timeoutMs, Integer maxRetries, String desc, int version,
                List<ParamDto> params, List<BodyDto> bodies, List<MappingDto> mappings,
                List<FieldDefDto> fieldDefs, List<BindingDto> bindings) {
            this(code, name, ifType, method, path, protocolIn, protocolOut, appId, groupId,
                    upstreamPath, callbackUrl, status, timeoutMs, maxRetries, desc,
                    BigDecimal.valueOf(version), params, bodies, mappings, fieldDefs, bindings);
        }
    }

    public record InterfaceResponse(
            long id, String code, String name, String ifType, String method, String path,
            String protocolIn, String protocolOut, String appId, Long groupId,
            String upstreamPath, String callbackUrl, String status, BigDecimal version,
            int timeoutMs, int maxRetries, String desc,
            LocalDateTime createdAt, LocalDateTime updatedAt,
            String appName, String groupName,
            List<InterfaceRow.ParamRow> params,
            List<InterfaceRow.BodyRow> bodies,
            List<InterfaceRow.MappingRow> mappings,
            List<InterfaceRow.FieldDefRow> fieldDefs,
            List<InterfaceRow.BindingRow> bindings
    ) {
    }
}
