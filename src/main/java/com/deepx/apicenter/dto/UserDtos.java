package com.deepx.apicenter.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

/**
 * 账号管理 DTO（v1，2026-09-18）。
 * 列表与详情**永不**返回口令摘要；`status` 仅 ENABLED / DISABLED。
 */
public final class UserDtos {

    private UserDtos() {
    }

    /** 列表行：带「有效会话数」与时间字段（无口令摘要） */
    public record UserRowView(long id, String username, String displayName, String status, int failedAttempts,
                              LocalDateTime lockedUntil, LocalDateTime lastLoginAt, LocalDateTime passwordUpdatedAt,
                              LocalDateTime createdAt, int sessionCount) {
    }

    public record CreateUserRequest(@NotBlank(message = "用户名不能为空") String username,
                                    @NotBlank(message = "密码不能为空") String password,
                                    String displayName) {
    }

    /** 编辑：显示名 + 状态（status 为空 = 不变） */
    public record UpdateUserRequest(String displayName, String status) {
    }

    public record ResetPasswordRequest(@NotBlank(message = "新密码不能为空") String newPassword) {
    }
}
