package com.deepx.apicenter.dto;

import com.deepx.apicenter.model.AppGroupRow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 分组管理 DTO（分组：应用下的组织单元，纯归类/展示）。
 */
public final class GroupDtos {

    private GroupDtos() {
    }

    public record GroupRequest(
            @NotBlank(message = "所属应用不能为空") String appId,
            @NotBlank(message = "分组名称不能为空") String name,
            @NotNull(message = "排序不能为空") Integer sortOrder
    ) {
    }

    public record GroupResponse(
            long id, String appId, String name, int sortOrder,
            LocalDateTime createdAt, long ifaceCount
    ) {
        public static GroupResponse from(AppGroupRow row) {
            return new GroupResponse(row.id(), row.appId(), row.name(), row.sortOrder(),
                    row.createdAt(), row.ifaceCount());
        }
    }
}
