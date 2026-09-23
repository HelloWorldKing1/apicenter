package com.deepx.apicenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台**入站鉴权**（调用方鉴权）配置 —— `app.api-center.client-auth`（2026-09-23，入站鉴权 B2）。
 *
 * <p>依据《开发文档/入站鉴权设计方案.md》v1.1 §14。**只作用于平台对外接口路径** `/{platformPath}` 的
 * **调用方方向**（Flow A）；供应商回调验签（Flow B）仍在链内，不受本配置影响（D-CA-3）。
 *
 * @param mode      灰度三态：{@code OFF}（默认，不校验=现状）/ {@code OPTIONAL}（有凭证就验、无凭证放行）/
 *                  {@code ENFORCED}（必须通过，fail-closed）
 * @param idHeader  主体标识头名（默认 {@code X-Client-Id}；单个调用方可由其适配器 params.idHeaderName 覆盖）
 * @param audit     审计（{@code access_auth_log}）相关开关
 * @param internalToken 内部调用令牌（D-CA-17）；**留空 = 不豁免**，仅在测试里显式设置
 * @param failAlertThreshold 连续鉴权失败告警阈值（5 分钟窗口，按主体；主体未知时按 IP）
 */
@ConfigurationProperties(prefix = "app.api-center.client-auth")
public record ClientAuthProperties(
        String mode,
        String idHeader,
        Audit audit,
        String internalToken,
        Integer failAlertThreshold) {

    public static final String DEFAULT_ID_HEADER = "X-Client-Id";

    /** 审计配置（写入实现在 B3；此处先提供口径） */
    public record Audit(Boolean enabled, Boolean recordPass, Boolean recordUnmatched, Integer retentionDays) {
    }

    public String modeOrDefault() {
        if (mode == null || mode.isBlank()) {
            return "OFF";
        }
        String m = mode.trim().toUpperCase();
        if (!"OFF".equals(m) && !"OPTIONAL".equals(m) && !"ENFORCED".equals(m)) {
            throw new IllegalArgumentException("client-auth.mode 仅支持 OFF / OPTIONAL / ENFORCED：" + mode);
        }
        return m;
    }

    public String idHeaderOrDefault() {
        return idHeader == null || idHeader.isBlank() ? DEFAULT_ID_HEADER : idHeader.trim();
    }

    public boolean auditEnabled() {
        return audit == null || audit.enabled() == null || audit.enabled();
    }

    public boolean recordPass() {
        return audit == null || audit.recordPass() == null || audit.recordPass();
    }

    /** 连续失败告警阈值（默认 10，与设计方案 §14 一致） */
    public int failAlertThresholdOrDefault() {
        return failAlertThreshold == null || failAlertThreshold <= 0 ? 10 : failAlertThreshold;
    }

    public String internalTokenOrNull() {
        return internalToken == null || internalToken.isBlank() ? null : internalToken;
    }
}
