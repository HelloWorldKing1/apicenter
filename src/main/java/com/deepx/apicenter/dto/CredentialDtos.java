package com.deepx.apicenter.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

/**
 * 凭证管理 DTO（M0-04）。管理面永不回显明文，仅指纹（尾 4 位）。
 * 例外：prepare / reset 生成新凭证时明文仅回显一次（生成响应），此后不可再读。
 */
public final class CredentialDtos {

    private CredentialDtos() {
    }

    /** 生成新凭证（平台生成随机值）：出站轮换第一步——先由供应商侧配置再激活 */
    public record PrepareRequest(@NotBlank(message = "凭证类型不能为空") String kind) {
    }

    /** 一步更新（供应商主动轮换回调密钥场景）：管理员录入供应商给的新凭证 */
    public record UpdateRequest(@NotBlank(message = "凭证类型不能为空") String kind,
                                @NotBlank(message = "凭证内容不能为空") String credential) {
    }

    /** 重置（应急语义）：新凭证入 ACTIVE、旧凭证全部立即失效 */
    public record ResetRequest(@NotBlank(message = "凭证类型不能为空") String kind,
                               @NotBlank(message = "凭证内容不能为空") String credential) {
    }

    /** 遮显视图：指纹 = 明文尾 4 位；expired = ROTATING 且并存窗口已过 */
    public record CredentialView(
            long id, String kind, String status, String fingerprint,
            LocalDateTime activatedAt, LocalDateTime retiredAt,
            LocalDateTime rotatingUntil, boolean expired
    ) {
    }

    /** 生成凭证的一次性回显（明文仅此一次，客户端需提示用户立即保存） */
    public record CredentialIssuedView(long id, String kind, String plaintext) {
    }
}
