package com.deepx.apicenter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 监控模块 DTO（M4 交付）：对账请求 / 告警规则请求 / 分页响应信封。
 */
public final class MonitorDtos {

    private MonitorDtos() {
    }

    /** 人工对账置位请求（D-M4-2）：operator 必填（管理面无用户体系，前端弹窗填写）；reason 可空 */
    public record ReconcileRequest(@NotBlank String target, @NotBlank String operator, String reason) {
    }

    /** 告警规则创建 / 更新请求：threshold 形如 "> 100" / "< 95" */
    public record AlertRuleRequest(@NotBlank String name, @NotBlank String metric,
                                   @NotBlank String threshold, String notifyChannel, boolean enabled) {
    }

    /** 分页响应信封（监控页五类列表共用） */
    public record PagedResponse<T>(java.util.List<T> list, long total, int page, int pageSize) {
        public static <T> PagedResponse<T> of(java.util.List<T> list, long total, int page, int pageSize) {
            return new PagedResponse<>(list, total, page, pageSize);
        }
    }
}
