package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.AdapterType;
import com.deepx.apicenter.engine.ChainPhase;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Bearer Token 出站鉴权（黄金用例依赖）：
 * OUTBOUND_AUTH 阶段向出站请求附加 Authorization 头。
 * token 取 ctx.attrs("outboundCredential")（M0-04：凭证统一存 app_credential，链引擎注入明文）；
 * headerName / prefix 取 adapter.params（默认 Authorization / Bearer）。
 */
@Component("BearerTokenAuthAdapter")
public class BearerTokenAuthAdapter implements Adapter {

    @Override
    public AdapterType type() {
        return AdapterType.AUTH;
    }

    @Override
    public AdapterContext process(AdapterContext ctx) {
        if (ctx.phase() != ChainPhase.OUTBOUND_AUTH) {
            return ctx;
        }
        JsonNode params = (JsonNode) ctx.attrs().get("adapterParams");
        String headerName = text(params, "headerName", "Authorization");
        String prefix = text(params, "prefix", "Bearer");
        // v1.2（2026-09-24）tokenSource：**模型点路径**优先于凭证 —— 用于「AK/SK 先换 token 再调业务」
        //    的编排场景（前置令牌步骤把 token 写到 `steps.<step>.access_token`，这里直接引用）。
        String tokenSource = params != null && params.hasNonNull("tokenSource") ? params.get("tokenSource").asText().trim() : "";
        Object fromModel = tokenSource.isBlank() || ctx.payload() == null ? null
                : ctx.payload().get(tokenSource).map(this::plainValue).orElse(null);
        Object credential = ctx.attrs().get("outboundCredential");
        Object token = fromModel != null ? fromModel : credential;
        if (token != null && !token.toString().isBlank()) {
            // prefix 为空 = 直发 token（2026-09-18 修复：原实现无条件 `prefix + " " + token`，
            // 当供应商要求裸 token（如 `Authorization: <token>` 或自定义头）时会把 prefix 置空，
            // 结果出现**前导空格** `" token"` → 多数服务端视为无效凭证）
            String value = prefix == null || prefix.isBlank() ? token.toString() : prefix + " " + token;
            ctx.outbound().header(headerName, value);
        } else {
            ctx.warn("BearerTokenAuthAdapter：出站凭证缺失（app_credential 无 ACTIVE OUTBOUND 凭证），未附加 "
                    + headerName + " 头");
        }
        return ctx;
    }

    private String text(JsonNode params, String key, String def) {
        if (params == null || !params.has(key) || params.get(key).isNull()) {
            return def;
        }
        return params.get(key).asText();
    }

    /** 取模型节点的"朴素值"（标量直接返回其 value；其他节点转字符串） */
    private Object plainValue(com.deepx.apicenter.engine.UnifiedModel.UNode node) {
        return node instanceof com.deepx.apicenter.engine.UnifiedModel.ScalarNode s
                ? s.value() : node.toString();
    }
}
