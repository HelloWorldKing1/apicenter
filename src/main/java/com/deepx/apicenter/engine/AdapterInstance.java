package com.deepx.apicenter.engine;

import tools.jackson.databind.JsonNode;

/**
 * 适配器链上实例（M0-01 §8）：委托 Bean + 适配器定义（adapter 表）的配置参数。
 * 适配器 Bean 全局单例（无状态），params 在每次 process 前注入 ctx.attrs("adapterParams")，
 * 使同一 impl 的多个适配器定义（不同参数）可复用同一 Bean。
 */
public record AdapterInstance(String adapterId, String impl, String version, Adapter delegate, JsonNode params) {

    public AdapterContext process(AdapterContext ctx) {
        ctx.attrs().put("adapterParams", params);
        ctx.attrs().put("adapterId", adapterId);
        return delegate.process(ctx);
    }
}
