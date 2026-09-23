package com.deepx.apicenter.aspect;

/**
 * 接入鉴权审计上下文（2026-09-23，入站鉴权 B2；设计方案 §10.1）：闸门（调用方鉴权）/ 链内（回调验签）
 * **登记**一次判定，网关切面 `finally` **清理**并异步落库（`access_auth_log`，写入在 B3）——
 * 与 {@link CallLogContext} 完全同构的纪律，防 ThreadLocal 泄漏（跨请求串账）。
 *
 * <p>⚠️ 拒绝可能发生在**引擎之前**（那时 `call_log` 的 `interface_id/app_id` 恒空），
 * 所以本上下文**自持**主体与接口信息（含名称快照），不依赖引擎填充。
 */
public final class AccessAuthContext {

    /** 一次鉴权判定的完整审计行（字段与 `access_auth_log` 一一对应） */
    public record Entry(
            String traceId,
            String direction,        // INBOUND_CALL 调用方→平台 / CALLBACK 供应商回调→平台
            String principalType,    // CLIENT / SUPPLIER
            String principalId,      // client_id / app_id
            String principalName,    // 名称快照（改名/删除后历史仍可读）
            Long interfaceId,
            String interfaceCode,
            String authMethod,       // API_KEY / HMAC-SHA256 / BEARER / IP_WHITELIST / PLATFORM_SELF / NONE
            String authAdapterId,
            String result,           // PASS / REJECT
            String errorCode,
            String reason,
            String clientIp,
            String xffChain,
            String userAgent,
            Long latencyMs) {
    }

    private static final ThreadLocal<Entry> HOLDER = new ThreadLocal<>();

    private AccessAuthContext() {
    }

    public static void set(Entry entry) {
        HOLDER.set(entry);
    }

    public static Entry get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
