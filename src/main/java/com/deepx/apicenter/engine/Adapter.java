package com.deepx.apicenter.engine;

/**
 * 适配器统一契约（M0-01 §2，定稿 D1）：
 * 链引擎只认 phase + process(ctx)，不感知适配器内部细节；
 * 协议适配器按 ctx.phase() 分流 DECODE 执行 decode、ENCODE 执行 encode；
 * 鉴权适配器分流 INBOUND_AUTH 执行 authenticate（结果写 ctx.inboundAuth）、
 * OUTBOUND_AUTH 执行 applyCredential（凭证写 ctx.outbound）。
 * 适配器无状态、配置驱动、全局单例；适配器定义（adapter 表）的参数经 AdapterInstance 注入 ctx。
 */
public interface Adapter {

    AdapterType type();

    /** 同阶段多实例时的排序（当前每阶段至多 1 个，保留扩展） */
    default int order() {
        return 0;
    }

    default boolean supports(AdapterContext ctx) {
        return true;
    }

    AdapterContext process(AdapterContext ctx);
}
