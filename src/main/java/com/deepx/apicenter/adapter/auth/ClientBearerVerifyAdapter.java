package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.InboundCredential;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * **调用方 Bearer Token 验签**（2026-09-23，B2；设计方案 §5.1 / §5.3）。
 *
 * <p>线协议（头名可配）：`X-Client-Id` + `Authorization: Bearer &lt;token&gt;`。
 * `prefix` **可置空 = 裸 token**（与出站 `BearerTokenAuthAdapter` 的 2026-09-18 修复同一口径：
 * 置空时**不得**拼出前导空格，否则供应商/平台两侧都会判为无效凭证）。
 */
@Component("ClientBearerVerifyAdapter")
public class ClientBearerVerifyAdapter extends AbstractClientVerifyAdapter {

    private static final String DEFAULT_HEADER = "Authorization";
    private static final String DEFAULT_PREFIX = "Bearer";

    @Override
    public String method() {
        return "BEARER";
    }

    @Override
    public String credentialKind() {
        return "BEARER_TOKEN";
    }

    @Override
    protected void verify(AdapterContext ctx) {
        var params = params(ctx);
        String idHeader = text(params, "idHeaderName", DEFAULT_ID_HEADER);
        String headerName = text(params, "headerName", DEFAULT_HEADER);
        // ⚠️ prefix 用 textOrEmpty：**显式置空 = 裸 token**（用 text 会把空串当"未配置"而回退 Bearer）
        String prefix = textOrEmpty(params, "prefix", DEFAULT_PREFIX);

        String raw = clientIdHeader(ctx, headerName);
        if (raw.isEmpty()) {
            throw fail(40100, "鉴权失败：缺少凭证头 " + headerName);
        }
        // prefix 置空 = 裸 token（不拼空格）；否则期望 "<prefix> <token>"
        String token = raw;
        if (!prefix.isBlank()) {
            String expect = prefix + " ";
            if (!raw.regionMatches(true, 0, expect, 0, expect.length())) {
                throw fail(40100, "鉴权失败：" + headerName + " 缺少前缀 " + prefix);
            }
            token = raw.substring(expect.length()).trim();
        }
        if (token.isEmpty()) {
            throw fail(40100, "鉴权失败：凭证为空");
        }
        // v1.2：候选凭证带 id/备注/指纹 —— 命中时由 matchCredential 回写，供审计归因（D-CA-20）
        List<InboundCredential> candidates = candidates(ctx);
        if (candidates.isEmpty()) {
            throw fail(40100, "鉴权失败：无可用凭证");
        }
        if (!matchCredential(ctx, candidates, token)) {
            throw fail(40100, "鉴权失败：Token 不匹配" + (idHeader.isBlank() ? "" : "（自报主体头 " + idHeader + "）"));
        }
    }
}
