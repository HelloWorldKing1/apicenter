package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.AdapterContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * **调用方 API Key 验签**（2026-09-23，B2；设计方案 §5.1 / §5.3）。
 *
 * <p>线协议（头名可配）：`X-Client-Id: ERP-PROD` + `X-Api-Key: &lt;secret&gt;`。
 * 校验：把 `X-Api-Key` 与注入的凭证（`API_KEY`，`ACTIVE` + `ROTATING`）**逐条常量时间比较**，任一命中即通过。
 */
@Component("ClientApiKeyVerifyAdapter")
public class ClientApiKeyVerifyAdapter extends AbstractClientVerifyAdapter {

    private static final String DEFAULT_CREDENTIAL_HEADER = "X-Api-Key";

    @Override
    public String method() {
        return "API_KEY";
    }

    @Override
    public String credentialKind() {
        return "API_KEY";
    }

    @Override
    protected void verify(AdapterContext ctx) {
        var params = params(ctx);
        String idHeader = text(params, "idHeaderName", DEFAULT_ID_HEADER);
        String keyHeader = text(params, "credentialHeaderName", DEFAULT_CREDENTIAL_HEADER);

        String presented = clientIdHeader(ctx, keyHeader);
        if (presented.isEmpty()) {
            throw fail(40100, "鉴权失败：缺少凭证头 " + keyHeader);
        }
        List<String> credentials = credentials(ctx);
        if (credentials.isEmpty()) {
            throw fail(40100, "鉴权失败：无可用凭证");
        }
        if (!anyCredentialMatches(credentials, presented)) {
            // 不暴露主体是否存在（枚举信息泄露最小化）；主体信息由审计表承载
            throw fail(40100, "鉴权失败：API Key 不匹配（主体由 " + idHeader + " 声明）");
        }
    }
}
