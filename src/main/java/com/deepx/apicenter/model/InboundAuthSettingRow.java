package com.deepx.apicenter.model;

import java.time.LocalDateTime;

/**
 * 入站鉴权平台设置行（**单行表** `inbound_auth_setting`，2026-09-24 v1.2 引入）。
 *
 * <p>为什么单独建表而不放配置：这两项是**日常运维/接入**要频繁调的旋钮（新增一个调用方、换一种鉴权方式
 * 不该重启服务）；而 `mode`（OFF/OPTIONAL/ENFORCED，上线与应急总开关）**刻意留在配置项** ——
 * 改它必须重启 = 一个「有意动作」，避免误点导致鉴权整体关闭（安全默认纪律，设计方案 v1.2 §14）。
 *
 * @param defaultAdapterId 平台默认入站鉴权方式（`adapter.type=auth` 的 id）；空 = 未配置 ⇒ fail-closed `40108`
 * @param requireClientId  `true` = 强制自报主体（v1.1 兼容档：缺失/未知 → `40107`）；
 *                         `false` = 开放集（`X-Client-Id` 仅入审计，不参与放行判定）
 * @param updatedBy        最后修改人（`admin_user.username` 快照）—— 回答「谁改的」
 * @param updatedAt        最后修改时间
 */
public record InboundAuthSettingRow(
        String defaultAdapterId,
        boolean requireClientId,
        String updatedBy,
        LocalDateTime updatedAt
) {
}
