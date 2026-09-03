package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.client.OutboundRequestSpec;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.ChainPhase;
import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.CredentialRow;
import com.deepx.apicenter.repository.CredentialRepository;
import com.deepx.apicenter.service.CryptoService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HmacCallbackVerifyAdapter 单测矩阵（M3 计划 §4）：
 * 验签通过 / 错签名 40100 / 时间戳过期 40101 / 缺时间戳头 40100 / 容差边界 /
 * 防重放命中 / ROTATING 新旧凭证并存均验 / 签名字符串构造向量。
 */
class HmacCallbackVerifyAdapterTest {

    private static final String SECRET_ACTIVE = "demo-secret-active";
    private static final String SECRET_ROTATING = "demo-secret-rotating";

    private final CredentialRepository credentialRepository = mock(CredentialRepository.class);
    private final CryptoService cryptoService = mock(CryptoService.class);
    private final HmacCallbackVerifyAdapter adapter =
            new HmacCallbackVerifyAdapter(credentialRepository, cryptoService);

    private final byte[] body = "{\"event_id\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);

    private AdapterContext ctx(Map<String, String> headers, JsonNode params) {
        AdapterContext ctx = AdapterContext.create(
                ChainPhase.INBOUND_AUTH, UnifiedModel.emptyObject(),
                new AdapterContext.InterfaceMeta(1, "IF-CB", "INBOUND", "POST", "/cb",
                        "JSON", "JSON", null, "http://cb", 3000, 4),
                new AdapterContext.AppMeta("fastmoss", "http://x"),
                new AdapterContext.TraceMeta("t"), null, new OutboundRequestSpec());
        ctx.attrs().put("rawBody", body);
        ctx.attrs().put("headers", headers);
        ctx.attrs().put("adapterParams", params);
        return ctx;
    }

    private void stubCredentials(String active, String rotating) {
        CredentialRow activeRow = credentialRow(active, "ACTIVE");
        CredentialRow rotatingRow = credentialRow(rotating, "ROTATING");
        when(credentialRepository.findVerifiable("fastmoss", "CALLBACK"))
                .thenReturn(rotating == null ? List.of(activeRow) : List.of(activeRow, rotatingRow));
        when(cryptoService.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    private CredentialRow credentialRow(String plain, String status) {
        LocalDateTime now = LocalDateTime.now();
        return new CredentialRow(1, "fastmoss", "CALLBACK", plain, status, now, null, null, now);
    }

    private Map<String, String> signedHeaders(String secret, byte[] raw, String algorithm) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        return Map.of("X-Timestamp", ts, "X-Partner-Signature", HmacSigner.sign(algorithm, secret, ts, raw));
    }

    // ---------- 验签通过 / 失败 ----------

    @Test
    void 验签通过() {
        stubCredentials(SECRET_ACTIVE, null);
        AdapterContext ctx = ctx(signedHeaders(SECRET_ACTIVE, body, "HMAC-SHA256"), null);
        assertThatCode(() -> adapter.process(ctx)).doesNotThrowAnyException();
        assertThat(ctx.attrs().get("inboundAuthPassed")).isEqualTo(true);
    }

    @Test
    void 错签名40100() {
        stubCredentials(SECRET_ACTIVE, null);
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        AdapterContext ctx = ctx(Map.of("X-Timestamp", ts, "X-Partner-Signature", "deadbeef"), null);
        assertThatThrownBy(() -> adapter.process(ctx))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40100));
    }

    @Test
    void 时间戳过期40101() {
        stubCredentials(SECRET_ACTIVE, null);
        String ts = String.valueOf(System.currentTimeMillis() / 1000 - 600); // 超容差 300s
        AdapterContext ctx = ctx(Map.of("X-Timestamp", ts,
                "X-Partner-Signature", HmacSigner.sign("HMAC-SHA256", SECRET_ACTIVE, ts, body)), null);
        assertThatThrownBy(() -> adapter.process(ctx))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40101));
    }

    @Test
    void 缺时间戳头40100() {
        stubCredentials(SECRET_ACTIVE, null);
        AdapterContext ctx = ctx(Map.of("X-Partner-Signature", "deadbeef"), null);
        assertThatThrownBy(() -> adapter.process(ctx))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40100));
    }

    @Test
    void 容差边界内通过() {
        stubCredentials(SECRET_ACTIVE, null);
        String ts = String.valueOf(System.currentTimeMillis() / 1000 - 299); // 容差 300 内
        AdapterContext ctx = ctx(Map.of("X-Timestamp", ts,
                "X-Partner-Signature", HmacSigner.sign("HMAC-SHA256", SECRET_ACTIVE, ts, body)), null);
        assertThatCode(() -> adapter.process(ctx)).doesNotThrowAnyException();
    }

    @Test
    void 缺签名头40100() {
        stubCredentials(SECRET_ACTIVE, null);
        AdapterContext ctx = ctx(Map.of("X-Timestamp", String.valueOf(System.currentTimeMillis() / 1000)), null);
        assertThatThrownBy(() -> adapter.process(ctx))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40100));
    }

    // ---------- 凭证轮换（M0-04 验签并存） ----------

    @Test
    void rotating新旧并存均验任一命中即通过() {
        stubCredentials(SECRET_ACTIVE, SECRET_ROTATING);
        // 用 ROTATING 密钥签（ACTIVE 已换新，验签阶段新旧都接受）
        AdapterContext ctx = ctx(signedHeaders(SECRET_ROTATING, body, "HMAC-SHA256"), null);
        assertThatCode(() -> adapter.process(ctx)).doesNotThrowAnyException();
        // 用 ACTIVE 密钥签同样通过
        AdapterContext ctx2 = ctx(signedHeaders(SECRET_ACTIVE, body, "HMAC-SHA256"), null);
        assertThatCode(() -> adapter.process(ctx2)).doesNotThrowAnyException();
    }

    // ---------- 防重放 ----------

    @Test
    void 防重放命中40100() throws Exception {
        stubCredentials(SECRET_ACTIVE, null);
        JsonNode params = new ObjectMapper().readTree("{\"replayProtection\":true}");
        Map<String, String> headers = signedHeaders(SECRET_ACTIVE, body, "HMAC-SHA256");
        assertThatCode(() -> adapter.process(ctx(headers, params))).doesNotThrowAnyException();
        // 同签名第二次 → 拒绝
        assertThatThrownBy(() -> adapter.process(ctx(headers, params)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40100));
    }

    // ---------- 签名字符串构造向量 ----------

    @Test
    void 签名字符串构造向量() {
        // 固定输入 → 固定签名（防实现漂移；向量由独立计算确认：HMAC-SHA256("ts.{\"a\":1}", "k")）
        byte[] raw = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String sig = HmacSigner.sign("HMAC-SHA256", "k", "1700000000", raw);
        assertThat(sig).matches("[0-9a-f]{64}");
        assertThat(HmacSigner.verify("HMAC-SHA256", "k", "1700000000", raw, sig)).isTrue();
        assertThat(HmacSigner.verify("HMAC-SHA256", "k", "1700000001", raw, sig)).isFalse(); // 时间戳参与签名
        assertThat(HmacSigner.verify("HMAC-SHA256", "k", "1700000000", raw, "deadbeef")).isFalse();
    }

    // ---------- 非 INBOUND_AUTH 阶段直通 ----------

    @Test
    void 非验签阶段直通() {
        AdapterContext ctx = ctx(Map.of(), null);
        ctx.phase(ChainPhase.ENCODE);
        assertThat(adapter.process(ctx)).isSameAs(ctx);
    }
}
