package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.AdapterType;
import com.deepx.apicenter.engine.ChainPhase;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * API Key 出站鉴权：向出站请求附加静态密钥头（如 X-API-Key）。
 * 密钥值取 ctx.attrs("outboundCredential")（凭证统一存 app_credential，M0-04）；
 * 头名取 adapter.params 的 headerName（默认 X-API-Key）。
 */
@Component("ApiKeyAuthAdapter")
public class ApiKeyAuthAdapter implements Adapter {

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
        String headerName = params != null && params.has("headerName") && !params.get("headerName").isNull()
                ? params.get("headerName").asText() : "X-API-Key";
        // v1.2（2026-09-24）tokenSource：**模型点路径**优先于凭证 —— 用于「AK/SK 先换 token 再调业务」
        //    的编排场景（前置令牌步骤把 token 写到 `steps.<step>.access_token`，这里直接引用）。
        String tokenSource = params != null && params.hasNonNull("tokenSource") ? params.get("tokenSource").asText().trim() : "";
        Object fromModel = tokenSource.isBlank() || ctx.payload() == null ? null
                : ctx.payload().get(tokenSource).map(this::plainValue).orElse(null);
        Object credential = ctx.attrs().get("outboundCredential");
        Object key = fromModel != null ? fromModel : credential;
        if (key != null && !key.toString().isBlank()) {
            ctx.outbound().header(headerName, key.toString());
        } else {
            ctx.warn("ApiKeyAuthAdapter：出站凭证缺失，未附加 " + headerName + " 头");
        }
        return ctx;
    }

    /** 取模型节点的"朴素值"（标量直接返回其 value；其他节点转字符串） */
    private Object plainValue(com.deepx.apicenter.engine.UnifiedModel.UNode node) {
        return node instanceof com.deepx.apicenter.engine.UnifiedModel.ScalarNode s
                ? s.value() : node.toString();
    }
}
