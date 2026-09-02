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
        Object token = ctx.attrs().get("outboundCredential");
        if (token != null && !token.toString().isBlank()) {
            ctx.outbound().header(headerName, prefix + " " + token);
        } else {
            ctx.warn("BearerTokenAuthAdapter：出站凭证缺失（app_credential 无 ACTIVE OUTBOUND 凭证），未附加 Authorization 头");
        }
        return ctx;
    }

    private String text(JsonNode params, String key, String def) {
        if (params == null || !params.has(key) || params.get(key).isNull()) {
            return def;
        }
        return params.get(key).asText();
    }
}
