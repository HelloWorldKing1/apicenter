package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.ChainPhase;
import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.CredentialRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.CredentialRepository;
import com.deepx.apicenter.service.AlertService;
import com.deepx.apicenter.service.CryptoService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 入站鉴权适配器单测（2026-09-23，B2）：四种方式的校验行为 + **HMAC 与回调验签同口径**（出口判据）
 * + 非 `INBOUND_AUTH` 阶段直通。
 */
class InboundAuthAdaptersTest {

    private final ClientApiKeyVerifyAdapter apiKey = new ClientApiKeyVerifyAdapter();
    private final ClientHmacVerifyAdapter hmac = new ClientHmacVerifyAdapter();
    private final ClientBearerVerifyAdapter bearer = new ClientBearerVerifyAdapter();
    private final ClientIpWhitelistVerifyAdapter ip = new ClientIpWhitelistVerifyAdapter();

    private final CredentialRepository credentialRepository = mock(CredentialRepository.class);
    private final CryptoService cryptoService = mock(CryptoService.class);
    private final AlertService alertService = mock(AlertService.class);

    private static InterfaceRow iface() {
        return new InterfaceRow(9, "IF-CLIENT-01", "调用方演示", "OUTBOUND", "POST", "/open/echo",
                "JSON", "JSON", "SOME-APP", 11L, "/upstream", null, "PUBLISHED", BigDecimal.ONE,
                3000, 4, "B2 单测", null, null, null, null, null);
    }

    private AdapterContext ctx(String paramsJson, Map<String, String> headers, String rawBody,
                               List<String> credentials) {
        AdapterContext ctx = AdapterContext.create(ChainPhase.INBOUND_AUTH, UnifiedModel.emptyObject(),
                AdapterContext.InterfaceMeta.of(iface()),
                new AdapterContext.AppMeta("APP-1", null),
                new AdapterContext.TraceMeta("trace-x"),
                AdapterContext.AuthResult.pass(null), null);
        try {
            ctx.attrs().put("adapterParams", new ObjectMapper().readTree(paramsJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ctx.attrs().put("headers", headers);
        ctx.attrs().put("rawBody", rawBody.getBytes(StandardCharsets.UTF_8));
        ctx.attrs().put("inboundCredentials", credentials);
        return ctx;
    }

    // ---------- API Key ----------

    @Test
    void APIKey_匹配即通过_不匹配与缺失均40100() {
        // 匹配（默认头名 X-Api-Key）
        AdapterContext ok = apiKey.process(ctx("{}", Map.of("X-Api-Key", "s1"), "{}", List.of("s1")));
        assertThat(ok.attrs()).containsEntry("inboundAuthPassed", true);
        assertThat(ok.attrs()).containsEntry("inboundAuthMethod", "API_KEY");

        assertThatThrownBy(() -> apiKey.process(ctx("{}", Map.of(), "{}", List.of("s1"))))
                .isInstanceOf(BizException.class).hasMessageContaining("缺少凭证头");
        assertThatThrownBy(() -> apiKey.process(ctx("{}", Map.of("X-Api-Key", "bad"), "{}", List.of("s1"))))
                .isInstanceOf(BizException.class).hasMessageContaining("不匹配");
        assertThatThrownBy(() -> apiKey.process(ctx("{}", Map.of("X-Api-Key", "s1"), "{}", List.of())))
                .isInstanceOf(BizException.class).hasMessageContaining("无可用凭证");
    }

    @Test
    void APIKey_多凭证并存时任一命中即通过_ROTATING语义() {
        AdapterContext ok = apiKey.process(ctx("{}", Map.of("X-Api-Key", "old"), "{}", List.of("new", "old")));
        assertThat(ok.attrs()).containsEntry("inboundAuthPassed", true);
    }

    @Test
    void APIKey_头名可配() {
        String params = "{\"credentialHeaderName\":\"X-Tenant-Key\",\"idHeaderName\":\"X-Tenant-Id\"}";
        AdapterContext ok = apiKey.process(ctx(params, Map.of("X-Tenant-Key", "s1"), "{}", List.of("s1")));
        assertThat(ok.attrs()).containsEntry("inboundAuthPassed", true);
    }

    // ---------- Bearer ----------

    @Test
    void Bearer_带前缀校验_大小写不敏感() {
        AdapterContext ok = bearer.process(ctx("{}", Map.of("Authorization", "Bearer tok-1"), "{}", List.of("tok-1")));
        assertThat(ok.attrs()).containsEntry("inboundAuthMethod", "BEARER");
        assertThat(bearer.process(ctx("{}", Map.of("Authorization", "bearer tok-1"), "{}", List.of("tok-1"))))
                .isNotNull();

        assertThatThrownBy(() -> bearer.process(ctx("{}", Map.of("Authorization", "tok-1"), "{}", List.of("tok-1"))))
                .isInstanceOf(BizException.class).hasMessageContaining("缺少前缀");
    }

    @Test
    void Bearer_前缀置空即裸token_不得产生前导空格() {
        // 2026-09-18 出站侧踩过同类坑（prefix 置空拼出 " token"）；入站侧同样必须发裸 token
        String params = "{\"prefix\":\"\"}";
        AdapterContext ok = bearer.process(ctx(params, Map.of("Authorization", "tok-1"), "{}", List.of("tok-1")));
        assertThat(ok.attrs()).containsEntry("inboundAuthPassed", true);
        // 头值两侧空白会被规整（HTTP 头本就允许 OWS）
        AdapterContext padded = bearer.process(ctx(params, Map.of("Authorization", " tok-1 "), "{}", List.of("tok-1")));
        assertThat(padded.attrs()).containsEntry("inboundAuthPassed", true);
        // 但裸 token 模式下**不会**自动去掉 "Bearer " 前缀（前缀是显式配置项，不做猜测）
        assertThatThrownBy(() -> bearer.process(ctx(params,
                Map.of("Authorization", "Bearer tok-1"), "{}", List.of("tok-1"))))
                .isInstanceOf(BizException.class);
    }

    // ---------- HMAC（与回调验签同口径） ----------

    @Test
    void HMAC_正确签名通过_时间戳超容差与缺失分别40101与40100() {
        String raw = "{\"a\":1}";
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = HmacSigner.sign("HMAC-SHA256", "hmac-secret", ts, raw.getBytes(StandardCharsets.UTF_8));

        AdapterContext ok = hmac.process(ctx("{}",
                Map.of("X-Signature", sig, "X-Timestamp", ts), raw, List.of("hmac-secret")));
        assertThat(ok.attrs()).containsEntry("inboundAuthMethod", "HMAC-SHA256");

        String old = String.valueOf(System.currentTimeMillis() / 1000 - 3600);
        String oldSig = HmacSigner.sign("HMAC-SHA256", "hmac-secret", old, raw.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> hmac.process(ctx("{}",
                Map.of("X-Signature", oldSig, "X-Timestamp", old), raw, List.of("hmac-secret"))))
                .isInstanceOf(BizException.class).hasMessageContaining("超出容差");

        assertThatThrownBy(() -> hmac.process(ctx("{}", Map.of("X-Signature", sig), raw, List.of("hmac-secret"))))
                .isInstanceOf(BizException.class).hasMessageContaining("缺少时间戳头");
    }

    @Test
    void HMAC_签名不匹配_40100() {
        String raw = "{\"a\":1}";
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        assertThatThrownBy(() -> hmac.process(ctx("{}",
                Map.of("X-Signature", "deadbeef", "X-Timestamp", ts), raw, List.of("hmac-secret"))))
                .isInstanceOf(BizException.class).hasMessageContaining("签名不匹配");
    }

    @Test
    void HMAC_与回调验签同口径_同一签名两边都通过() {
        // 出口判据：复用 HmacSigner + timestamp + "." + rawBody 口径 ⇒ 同一份联调脚本两边通用
        String raw = "{\"event\":\"evt-1\"}";
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        byte[] body = raw.getBytes(StandardCharsets.UTF_8);
        String sig = HmacSigner.sign("HMAC-SHA256", "shared-secret", ts, body);

        // ① 调用方侧（凭证由注入方提供）
        AdapterContext clientCtx = hmac.process(ctx("{}",
                Map.of("X-Signature", sig, "X-Timestamp", ts), raw, List.of("shared-secret")));
        assertThat(clientCtx.attrs()).containsEntry("inboundAuthPassed", true);

        // ② 供应商回调侧（M3 既有实现：自己查库）
        when(credentialRepository.findVerifiable("APP-1", "CALLBACK"))
                .thenReturn(List.of(new CredentialRow(1, "APP-1", "CALLBACK", "enc", "ACTIVE", null, null, null, null, null)));
        when(cryptoService.decrypt(anyString())).thenReturn("shared-secret");
        HmacCallbackVerifyAdapter callback = new HmacCallbackVerifyAdapter(credentialRepository, cryptoService,
                alertService);
        AdapterContext callbackCtx = AdapterContext.create(ChainPhase.INBOUND_AUTH, UnifiedModel.emptyObject(),
                AdapterContext.InterfaceMeta.of(iface()), new AdapterContext.AppMeta("APP-1", null),
                new AdapterContext.TraceMeta("trace-x"), AdapterContext.AuthResult.pass(null), null);
        callbackCtx.attrs().put("headers", Map.of("X-Partner-Signature", sig, "X-Timestamp", ts));
        callbackCtx.attrs().put("rawBody", body);
        callback.process(callbackCtx);
        assertThat(callbackCtx.attrs()).containsEntry("inboundAuthPassed", true);
    }

    // ---------- 仅 IP 名单 ----------

    @Test
    void 仅IP名单_以闸门判定结果为准() {
        AdapterContext allowed = ctx("{}", Map.of(), "{}", List.of());
        allowed.attrs().put("clientIpAllowed", true);
        assertThat(ip.process(allowed).attrs()).containsEntry("inboundAuthMethod", "IP_WHITELIST");
        assertThat(ip.credentialKind()).isNull();

        AdapterContext denied = ctx("{}", Map.of(), "{}", List.of());
        denied.attrs().put("clientIpAllowed", false);
        assertThatThrownBy(() -> ip.process(denied))
                .isInstanceOf(BizException.class).hasMessageContaining("不在白名单");
    }

    // ---------- 阶段直通 ----------

    @Test
    void 非入站鉴权阶段_一律直通不校验() {
        AdapterContext outboundPhase = AdapterContext.create(ChainPhase.ENCODE, UnifiedModel.emptyObject(),
                AdapterContext.InterfaceMeta.of(iface()), new AdapterContext.AppMeta("APP-1", null),
                new AdapterContext.TraceMeta("t"), AdapterContext.AuthResult.pass(null), null);
        // 无任何凭证/头也应原样返回（不抛异常）
        assertThat(apiKey.process(outboundPhase)).isSameAs(outboundPhase);
        assertThat(hmac.process(outboundPhase)).isSameAs(outboundPhase);
        assertThat(bearer.process(outboundPhase)).isSameAs(outboundPhase);
        assertThat(ip.process(outboundPhase)).isSameAs(outboundPhase);
    }
}
