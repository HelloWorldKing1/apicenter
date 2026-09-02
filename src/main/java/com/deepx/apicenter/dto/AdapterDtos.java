package com.deepx.apicenter.dto;

import com.deepx.apicenter.model.AdapterRow;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 适配器管理 DTO。params 为 JSON 字符串（按 impl 元数据 schema 校验，
 * 校验通过后原样落库）；凭证类参数不落 params（统一走应用凭证管理）。
 */
public final class AdapterDtos {

    private AdapterDtos() {
    }

    public record AdapterRequest(
            String id,
            @NotBlank(message = "适配器名称不能为空") String name,
            @NotBlank(message = "适配器类型不能为空") String type,
            @NotBlank(message = "实现类不能为空") String impl,
            Boolean enabled,
            String version,
            String params
    ) {
    }

    public record AdapterResponse(
            String id, String name, String type, String impl, boolean enabled,
            String version, String params, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
        public static AdapterResponse from(AdapterRow row) {
            return new AdapterResponse(row.id(), row.name(), row.type(), row.impl(), row.enabled(),
                    row.version(), row.params(), row.createdAt(), row.updatedAt());
        }
    }

    /** impl 元数据：管理面据此动态渲染参数表单（原型 ADAPTER_FIELDS 模式） */
    public record ImplField(String key, String label, String kind, boolean required, List<String> options) {
    }

    /** impl 元数据条目 */
    public record ImplMeta(String impl, String type, String name, List<ImplField> fields) {
    }
}
