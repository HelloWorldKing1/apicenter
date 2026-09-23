package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.AdapterContext;
import org.springframework.stereotype.Component;

/**
 * **仅 IP 名单**的调用方鉴权（2026-09-23，B2；设计方案 §5.1 / D-CA-7）。
 *
 * <p>不读凭证（{@link #credentialKind()} 返回 `null`）：通过条件是**调用方维度 IP 白名单命中**
 * —— 名单判定在闸门的步骤 ②（`ClientAuthVerifier`）完成并把结果放进 `attrs("clientIpAllowed")`，
 * 本类只**断言该结论**（保持"适配器不查库"）。
 *
 * <p>⚠️ D-CA-7 的 fail-closed 要求：「白名单必须非空，否则拒」由闸门在解析调用方时校验
 * （白名单为空时 `clientIpAllowed` 不会为 true）⇒ 本类自然拒绝。
 */
@Component("ClientIpWhitelistVerifyAdapter")
public class ClientIpWhitelistVerifyAdapter extends AbstractClientVerifyAdapter {

    @Override
    public String method() {
        return "IP_WHITELIST";
    }

    /** 不读凭证 */
    @Override
    public String credentialKind() {
        return null;
    }

    @Override
    protected void verify(AdapterContext ctx) {
        Object allowed = ctx.attrs().get("clientIpAllowed");
        if (!Boolean.TRUE.equals(allowed)) {
            throw fail(40103, "鉴权失败：来源 IP 不在白名单（或白名单未配置）");
        }
    }
}
