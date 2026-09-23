package com.deepx.apicenter.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 入站鉴权平台设置 DTO（2026-09-24 v1.2）。
 *
 * <p>对应单行表 `inbound_auth_setting`（页面可改、改后即时生效，无需重启）。
 * `mode`（OFF/OPTIONAL/ENFORCED）**不在其中** —— 它留在配置项（改它必须重启 = 有意动作，安全默认纪律）。
 */
public final class InboundAuthSettingDtos {

    private InboundAuthSettingDtos() {
    }

    /** 保存请求：平台默认鉴权方式（可空 = 未配置 ⇒ fail-closed 40108）+ 是否强制自报主体 */
    public record SettingRequest(String defaultAdapterId,
                                 @NotNull(message = "requireClientId 不能为空") Boolean requireClientId) {
    }

    /** 设置视图（含最后修改人/时间，回答「谁改的」「什么时候改的」） */
    public record SettingView(String defaultAdapterId, String defaultAdapterName,
                              boolean requireClientId, String updatedBy, String updatedAt) {
    }

    /** 影响面预览：本次变更会影响多少个接口（未绑定 `CLIENT_AUTH` 的出站中转接口） */
    public record ImpactView(int affectedInterfaces, String hint) {
    }
}
