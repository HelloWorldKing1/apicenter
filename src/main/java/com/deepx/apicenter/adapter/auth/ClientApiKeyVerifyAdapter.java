package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.InboundCredential;
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
        // v1.2：候选凭证带 id/备注/指纹 —— 命中时由 matchCredential 回写，供审计归因（D-CA-20）
        List<InboundCredential> candidates = candidates(ctx);
        if (candidates.isEmpty()) {
            throw fail(40100, "鉴权失败：无可用凭证");
        }
        if (!matchCredential(ctx, candidates, presented)) {
            // 不暴露「密钥是否存在/主体是否存在」（枚举信息泄露最小化）；归因信息由审计表承载
            throw fail(40100, "鉴权失败：API Key 不匹配" + (idHeader.isBlank() ? "" : "（自报主体头 " + idHeader + "）"));
        }
    }
}
