package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.Adapter;

/**
 * **入站鉴权**适配器契约（2026-09-23，入站鉴权 B2 / D-CA-6）：{@link Adapter} 的扩展子接口。
 *
 * <p>与既有 {@code HmacCallbackVerifyAdapter}（M3 回调验签）的关键差别是**不查库**：
 * 适配器是**无状态、配置驱动**的（M0-01 §5.2），凭证由**注入方**（`ClientAuthVerifier`）
 * 经 {@code ctx.attrs("inboundCredentials")} 提供（`List&lt;String&gt;`：解密后的明文，
 * `ACTIVE` + `ROTATING` 并存、任一命中即通过）。
 *
 * <p>**同一批实现服务两处**（§9.3）：校验逻辑与「是谁的凭证」无关 ⇒ 既可被 `client_app.auth_adapter_id` 引用
 * （调用方鉴权），也可绑到接口 `CALLBACK_AUTH` 角色（回调验签）。
 *
 * <p>约定的上下文字段（由注入方放入 {@code ctx.attrs()}）：
 * <ul>
 *   <li>{@code adapterParams} → `JsonNode`：适配器实例的 params（非密参数：头名 / 算法 / 容差…）；</li>
 *   <li>{@code headers} → `Map&lt;String,String&gt;`：入站请求头；</li>
 *   <li>{@code rawBody} → `byte[]`：原始报文（HMAC 签名串要用）；</li>
 *   <li>{@code inboundCredentials} → `List&lt;String&gt;`：注入的凭证明文（**适配器只读，不查库**）；</li>
 *   <li>{@code clientIpAllowed} → `Boolean`：调用方维度 IP 名单判定结果（仅 IP 名单方式需要）。</li>
 * </ul>
 *
 * <p>失败一律抛 {@code BizException}：`40100` 凭证/签名不匹配、`40101` 时间戳超容差、`40103` 来源 IP 被拒。
 */
public interface InboundAuthAdapter extends Adapter {

    /** 审计口径的**方式名**（写入 `access_auth_log.auth_method`），如 `API_KEY` / `HMAC-SHA256` / `BEARER` / `IP_WHITELIST` */
    String method();

    /**
     * 期望的凭证类型（对应 `client_credential.kind` / `app_credential.kind`）；
     * **不读凭证的方式返回 `null`**（如仅 IP 名单）——注入方据此决定是否注入凭证。
     */
    String credentialKind();
}
