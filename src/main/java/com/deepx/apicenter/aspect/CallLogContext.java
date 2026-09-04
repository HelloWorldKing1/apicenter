package com.deepx.apicenter.aspect;

/**
 * 调用日志上下文（M4 交付，D-M4-4）：引擎入口填充本次请求的接口 / 应用 / traceId 元数据，
 * 网关侧 CallLogAspect 在 proceed 之后读取，用于 IN 方向 call_log 落库。
 *
 * <p>清理契约（防 ThreadLocal 泄漏，M4 计划风险 #4）：<b>引擎设置、调用方清理</b>——
 * 网关切面 finally 清理（正常路径）；管理面调试端点（/test、/mock-callback 直调引擎、
 * 不经网关、有意不落 IN 条 call_log）在各自 finally 清理。
 */
public final class CallLogContext {

    public record Meta(long interfaceId, String appId, String traceId) {
    }

    private static final ThreadLocal<Meta> HOLDER = new ThreadLocal<>();

    private CallLogContext() {
    }

    public static void set(long interfaceId, String appId, String traceId) {
        HOLDER.set(new Meta(interfaceId, appId, traceId));
    }

    public static Meta get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
