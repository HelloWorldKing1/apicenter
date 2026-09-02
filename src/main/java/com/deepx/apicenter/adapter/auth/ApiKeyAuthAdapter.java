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
        Object key = ctx.attrs().get("outboundCredential");
        if (key != null && !key.toString().isBlank()) {
            ctx.outbound().header(headerName, key.toString());
        } else {
            ctx.warn("ApiKeyAuthAdapter：出站凭证缺失，未附加 " + headerName + " 头");
        }
        return ctx;
    }
}
