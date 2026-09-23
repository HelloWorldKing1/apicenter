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
        // ① 档案侧名单（若自报主体命中档案，闸门算好 clientIpAllowed 传入）—— 与方式参数名单 **AND 叠加**
        Object allowed = ctx.attrs().get("clientIpAllowed");
        if (Boolean.FALSE.equals(allowed)) {
            throw fail(40103, "鉴权失败：来源 IP 被拒（不在白名单 / 命中黑名单，档案名单）");
        }
        // ② 方式参数侧名单（v1.2：名单本体进 params，不登记调用方也能用）
        String whitelist = text(params(ctx), "ipWhitelist", "");
        String blacklist = text(params(ctx), "ipBlacklist", "");
        String clientIp = ctx.attrs().get("clientIp") instanceof String s ? s : null;
        if (clientIp == null) {
            throw fail(40103, "鉴权失败：无法取到来源 IP");
        }
        if (!blacklist.isBlank() && listContains(blacklist, clientIp)) {
            throw fail(40103, "鉴权失败：来源 IP 命中黑名单（" + clientIp + "）");
        }
        if (!whitelist.isBlank()) {
            if (!listContains(whitelist, clientIp)) {
                throw fail(40103, "鉴权失败：来源 IP 不在白名单（" + clientIp + "）");
            }
            return;
        }
        // 未配白名单：仅当档案侧明确允许（TRUE）才放行 —— fail-closed，绝不回退「无名单即放行」
        if (!Boolean.TRUE.equals(allowed)) {
            throw fail(40103, "鉴权失败：未配置白名单（方式参数与档案名单均无）—— fail-closed，不回退「无名单即放行」");
        }
    }

    /** 逗号分隔名单的精确匹配（与 GatewayGuard / 档案名单同口径） */
    private static boolean listContains(String list, String ip) {
        for (String part : list.split(",")) {
            if (part.trim().equals(ip)) {
                return true;
            }
        }
        return false;
    }
}
