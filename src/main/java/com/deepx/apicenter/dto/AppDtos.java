package com.deepx.apicenter.dto;

import com.deepx.apicenter.model.AppRow;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用管理 DTO（请求 / 响应）。凭证不在应用模型内（app_credential 表），
 * 详情响应携带遮显视图 {@link CredentialDtos.CredentialView}。
 */
public final class AppDtos {

    private AppDtos() {
    }

    /** 创建 / 更新请求（创建时 appId 必填；更新时 appId 用于定位） */
    public record AppRequest(
            @NotBlank(message = "应用标识不能为空") String appId,
            @NotBlank(message = "应用名称不能为空") String name,
            String contact,
            String authAdapterId,
            String callbackAuthAdapterId,
            String defaultMessageAdapterId,
            String baseUrl,
            String ipWhitelist,
            String ipBlacklist,
            Integer qpsLimit,
            Long dailyQuota,
            String desc
    ) {
    }

    /** 应用详情 / 列表响应 */
    public record AppResponse(
            String appId, String name, String contact,
            String authAdapterId, String callbackAuthAdapterId, String defaultMessageAdapterId,
            String baseUrl, String ipWhitelist, String ipBlacklist,
            Integer qpsLimit, Long dailyQuota,
            String status, String desc,
            LocalDateTime createdAt, LocalDateTime updatedAt,
            long groupCount, long ifaceCount,
            List<CredentialDtos.CredentialView> credentials
    ) {
        public static AppResponse from(AppRow row) {
            return new AppResponse(
                    row.appId(), row.name(), row.contact(),
                    row.authAdapterId(), row.callbackAuthAdapterId(), row.defaultMessageAdapterId(),
                    row.baseUrl(), row.ipWhitelist(), row.ipBlacklist(),
                    row.qpsLimit(), row.dailyQuota(), row.status(), row.desc(),
                    row.createdAt(), row.updatedAt(), row.groupCount(), row.ifaceCount(), List.of());
        }
    }
}
