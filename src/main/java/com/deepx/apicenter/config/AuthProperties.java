package com.deepx.apicenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理面账号登录配置（app.api-center.auth，2026-09-18）。
 *
 * <p>范围：**只做认证**（登录 / 注册 / 退出 / 改密），v1 不做权限与角色；保护对象 = `/api/admin/**`
 * （`/api/admin/auth/{login,register,status}` 豁免）。**不涉及**平台对外接口路径（`/{platformPath}`），
 * 调用方鉴权是另一特性（设计 §1.2 / §5.3）。
 *
 * @param enabled               总开关（false = 完全不校验，用于集成测试与应急回退）
 * @param allowRegister         是否允许开放注册；**系统尚无任何账号时永远允许**（首次初始化）
 * @param sessionTtlHours       会话令牌有效期（小时）
 * @param renewIntervalMinutes  惰性续期间隔：距上次续期超过该值才写一次（控制写放大，≤1 次/会话·小时）
 * @param maxFailedAttempts     连续失败次数阈值（达到即锁定）
 * @param lockMinutes           锁定时长（分钟）
 */
@ConfigurationProperties(prefix = "app.api-center.auth")
public record AuthProperties(
        Boolean enabled,
        Boolean allowRegister,
        Integer sessionTtlHours,
        Integer renewIntervalMinutes,
        Integer maxFailedAttempts,
        Integer lockMinutes) {

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public boolean allowRegisterOrDefault() {
        return allowRegister == null || allowRegister;
    }

    public int sessionTtlHoursOrDefault() {
        return sessionTtlHours == null || sessionTtlHours <= 0 ? 12 : sessionTtlHours;
    }

    public int renewIntervalMinutesOrDefault() {
        return renewIntervalMinutes == null || renewIntervalMinutes <= 0 ? 60 : renewIntervalMinutes;
    }

    public int maxFailedAttemptsOrDefault() {
        return maxFailedAttempts == null || maxFailedAttempts <= 0 ? 5 : maxFailedAttempts;
    }

    public int lockMinutesOrDefault() {
        return lockMinutes == null || lockMinutes <= 0 ? 5 : lockMinutes;
    }
}
