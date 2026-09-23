package com.deepx.apicenter.config;

/**
 * 入站鉴权平台设置变更事件（2026-09-24 v1.2）。
 *
 * <p><b>为什么不复用 {@link ConfigChangedEvent}</b>：那个事件的 `Scope` 只有 `INTERFACE` / `ADAPTER` / `APP`，
 * 而 {@code ChainEngine} 的监听器是**穷尽 switch**（新增取值会连带改链引擎）；更重要的是
 * 「平台默认入站鉴权方式」**不影响链缓存**（闸门在链之外）—— 硬塞进 `APP` 会让链缓存被无谓全清，
 * 且把日志语义写错（「影响面不定」）。
 *
 * <p><b>谁监听</b>：{@code InboundAuthSettingService} 自身（失效自己的内存缓存）。
 * <b>为什么要事件而不是只直接失效</b>：保存路径与读取路径可能在不同实例/不同 Bean 形态下演化
 * （例如将来把设置读进闸门的链装配），事件是「新增配置入口必须补发」这条纪律的统一落点。
 * 另有 **60s TTL 兜底**（多实例部署下其他实例最长 60s 内生效，与链缓存 TTL 兜底同思路）。
 *
 * @param defaultAdapterId 变更后的平台默认鉴权方式（可空）
 * @param requireClientId  变更后的「是否强制自报主体」
 * @param changedBy        操作者（`admin_user.username`）
 * @param relaxation       是否属于「放松类」变更（`require_client_id` 1→0，或方式发生变更）—— 供告警/审计措辞
 */
public record InboundAuthSettingChangedEvent(
        String defaultAdapterId,
        boolean requireClientId,
        String changedBy,
        boolean relaxation
) {
}
