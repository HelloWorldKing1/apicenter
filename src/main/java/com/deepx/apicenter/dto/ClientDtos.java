package com.deepx.apicenter.dto;

import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调用方（平台客户 / 接入方）DTO —— 2026-09-23 入站鉴权 B1。
 *
 * <p>凭证相关视图复用 {@link CredentialDtos.CredentialView}（口径与 M0-04 完全一致：只回尾 4 指纹，永不回显明文）。
 */
public final class ClientDtos {

    private ClientDtos() {
    }

    /** 新建 / 更新调用方（启停用是独立端点，不在此提交 status） */
    public record ClientRequest(
            @NotBlank(message = "调用方标识不能为空") String clientId,
            @NotBlank(message = "调用方名称不能为空") String name,
            String contact,
            String authAdapterId,
            String ipWhitelist,
            String ipBlacklist,
            Integer qpsLimit,
            Long dailyQuota,
            String desc
    ) {
    }

    /** 列表 / 详情响应；`activeCredentialKinds` = 已有 ACTIVE 凭证的类型（列表一次 IN 查询补齐，避免 N+1） */
    public record ClientResponse(
            long id, String clientId, String name, String contact,
            String authAdapterId,
            String ipWhitelist, String ipBlacklist,
            Integer qpsLimit, Long dailyQuota,
            String status, String desc,
            LocalDateTime createdAt, LocalDateTime updatedAt,
            List<String> activeCredentialKinds
    ) {
    }

    /** 详情 = 调用方 + 凭证遮显列表 */
    public record ClientDetail(ClientResponse client, List<CredentialView> credentials) {
    }
}
