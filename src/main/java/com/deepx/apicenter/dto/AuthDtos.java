package com.deepx.apicenter.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

/**
 * 管理面账号登录相关 DTO（2026-09-18）。
 * 令牌只在 {@link LoginView#token} 出现一次（服务端只存摘要）；口令字段永不回显。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank(message = "用户名不能为空") String username,
                               @NotBlank(message = "密码不能为空") String password) {
    }

    public record RegisterRequest(@NotBlank(message = "用户名不能为空") String username,
                                  @NotBlank(message = "密码不能为空") String password,
                                  String displayName) {
    }

    public record ChangePasswordRequest(@NotBlank(message = "原密码不能为空") String oldPassword,
                                        @NotBlank(message = "新密码不能为空") String newPassword) {
    }

    /** 登录/注册成功响应：token = 明文令牌（仅此一次），expiresAt = 过期时间 */
    public record LoginView(String token, LocalDateTime expiresAt, UserView user) {
    }

    public record UserView(long id, String username, String displayName, LocalDateTime lastLoginAt) {
    }

    /** 登录页引导信息（免鉴权）：是否开启认证 / 是否已有账号（首次初始化）/ 是否允许注册 */
    public record AuthStatusView(boolean enabled, boolean hasUser, boolean allowRegister) {
    }
}
