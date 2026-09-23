package com.deepx.apicenter.service;

import com.deepx.apicenter.adapter.auth.ClientApiKeyVerifyAdapter;
import com.deepx.apicenter.adapter.auth.ClientBearerVerifyAdapter;
import com.deepx.apicenter.adapter.auth.ClientHmacVerifyAdapter;
import com.deepx.apicenter.adapter.auth.ClientIpWhitelistVerifyAdapter;
import com.deepx.apicenter.aspect.AccessAuthContext;
import com.deepx.apicenter.config.ClientAuthProperties;
import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.model.AdapterRow;
import com.deepx.apicenter.model.ClientAppRow;
import com.deepx.apicenter.model.CredentialRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.ClientAppRepository;
import com.deepx.apicenter.repository.CredentialOwner;
import com.deepx.apicenter.repository.CredentialRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 调用方鉴权闸门内核单测（2026-09-23，B2，Mockito 不连库）：**§6.2 模式真值表逐行覆盖** +
 * 步骤 ⓪（`OPTIONS` / 内部令牌）+ IP 名单 + 审计上下文 + **反证（适配器缺失/停用不回退 Noop）**。
 */
class ClientAuthVerifierTest {

    private static final String CLIENT = "ERP-PROD";
    private static final String INTERNAL = "test-internal-token";

    private final ClientAppRepository clientRepo = mock(ClientAppRepository.class);
    private final AdapterRepository adapterRepo = mock(AdapterRepository.class);
    private final CredentialRepository credentialRepo = mock(CredentialRepository.class);
    private final CryptoService cryptoService = mock(CryptoService.class);
    private final AlertService alertService = mock(AlertService.class);

    private ClientAuthVerifier verifier(String mode) {
        ClientAuthProperties props = new ClientAuthProperties(mode, null, null, INTERNAL, null);
        InternalCallToken token = new InternalCallToken(props);
        Map<String, Adapter> beans = Map.of(
                "ClientApiKeyVerifyAdapter", new ClientApiKeyVerifyAdapter(),
                "ClientHmacVerifyAdapter", new ClientHmacVerifyAdapter(),
                "ClientBearerVerifyAdapter", new ClientBearerVerifyAdapter(),
                "ClientIpWhitelistVerifyAdapter", new ClientIpWhitelistVerifyAdapter());
        return new ClientAuthVerifier(props, token, clientRepo, adapterRepo, credentialRepo, cryptoService,
                new ObjectMapper(), beans, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                alertService);
    }

    @AfterEach
    void clearAudit() {
        AccessAuthContext.clear();
    }

    // ---------- 夹具 ----------

    private InterfaceRow iface() {
        return new InterfaceRow(9, "IF-CLIENT-01", "调用方演示", "OUTBOUND", "POST", "/open/echo",
                "JSON", "JSON", "SOME-APP", 11L, "/upstream", null, "PUBLISHED", BigDecimal.ONE,
                3000, 4, "B2 单测", null, null, null, null, null);
    }

    private ClientAppRow client(String status, String adapterId, String whitelist, String blacklist) {
        return new ClientAppRow(1L, CLIENT, "ERP 生产", "张三", adapterId, whitelist, blacklist,
                null, null, status, "B2 单测", null, null);
    }

    private AdapterRow adapter(String id, String impl, boolean enabled, String params) {
        return new AdapterRow(id, "验签器", "auth", impl, enabled, "1.0", params, null, null);
    }

    private void givenClient(ClientAppRow row) {
        when(clientRepo.findById(CLIENT)).thenReturn(Optional.of(row));
    }

    private void givenApiKeyAdapter(boolean enabled) {
        givenClient(client("ENABLED", "ADP-K1", null, null));
        when(adapterRepo.findById("ADP-K1"))
                .thenReturn(Optional.of(adapter("ADP-K1", "ClientApiKeyVerifyAdapter", enabled, null)));
        when(credentialRepo.findVerifiable(eq(CredentialOwner.CLIENT), eq(CLIENT), eq("API_KEY")))
                .thenReturn(List.of(new CredentialRow(1, CLIENT, "API_KEY", "enc", "ACTIVE", null, null, null, null, null)));
        when(cryptoService.decrypt("enc")).thenReturn("secret-1234");
    }

    private ClientAuthVerifier.Decision verify(String mode, Map<String, String> headers) {
        return verifier(mode).verify(iface(), "POST", headers, "{}".getBytes(), "1.2.3.4", null, "curl/8", "trace-1");
    }

    // ---------- §6.2 模式真值表 ----------

    @Test
    void OFF_无主体头_放行且方式为NONE() {
        ClientAuthVerifier.Decision d = verify("OFF", Map.of());
        assertThat(d.passed()).isTrue();
        assertThat(d.authMethod()).isEqualTo("NONE");
    }

    @Test
    void OFF_有主体头_不校验凭证即放行() {
        givenClient(client("ENABLED", null, null, null));   // 未配鉴权方式也不拦（OFF = 现状）
        ClientAuthVerifier.Decision d = verify("OFF", Map.of("X-Client-Id", CLIENT));
        assertThat(d.passed()).isTrue();
        assertThat(d.authMethod()).isEqualTo("NONE");
    }

    @Test
    void OFF_无主体但带了凭证头_仍放行_真值表第一行() {
        // 全量套件抓到的回归（M4IntegrationTest.c6）：既有调用方**自带 Authorization 头**，
        // 但平台未启用鉴权（mode=OFF）⇒ 必须一律跳过（不校验、不报错），
        // 不能在"带了凭证却没主体"时误判为 40107（那条只对 OPTIONAL 生效）。
        ClientAuthVerifier.Decision d = verify("OFF", Map.of("Authorization", "Bearer caller-token-abcdef123456"));
        assertThat(d.passed()).isTrue();
        assertThat(d.authMethod()).isEqualTo("NONE");
    }

    @Test
    void OFF_但主体不可识别_仍拒40107() {
        when(clientRepo.findById("UNKNOWN")).thenReturn(Optional.empty());
        ClientAuthVerifier.Decision d = verify("OFF", Map.of("X-Client-Id", "UNKNOWN"));
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40107);
    }

    @Test
    void OPTIONAL_无主体头且无凭证_放行() {
        ClientAuthVerifier.Decision d = verify("OPTIONAL", Map.of());
        assertThat(d.passed()).isTrue();
        assertThat(d.authMethod()).isEqualTo("NONE");
    }

    @Test
    void OPTIONAL_无主体头但带了凭证_拒40107() {
        ClientAuthVerifier.Decision d = verify("OPTIONAL", Map.of("X-Api-Key", "whatever"));
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40107);
        assertThat(d.message()).contains("缺少主体标识头");
    }

    @Test
    void OPTIONAL_有主体头但无凭证_拒40100() {
        givenApiKeyAdapter(true);
        ClientAuthVerifier.Decision d = verify("OPTIONAL", Map.of("X-Client-Id", CLIENT));
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40100);
    }

    @Test
    void OPTIONAL_有主体头且有正确凭证_放行() {
        givenApiKeyAdapter(true);
        ClientAuthVerifier.Decision d = verify("OPTIONAL",
                Map.of("X-Client-Id", CLIENT, "X-Api-Key", "secret-1234"));
        assertThat(d.passed()).isTrue();
        assertThat(d.authMethod()).isEqualTo("API_KEY");
    }

    @Test
    void ENFORCED_无主体头_拒40107() {
        ClientAuthVerifier.Decision d = verify("ENFORCED", Map.of());
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40107);
    }

    @Test
    void ENFORCED_正确凭证_放行() {
        givenApiKeyAdapter(true);
        ClientAuthVerifier.Decision d = verify("ENFORCED",
                Map.of("X-Client-Id", CLIENT, "X-Api-Key", "secret-1234"));
        assertThat(d.passed()).isTrue();
        assertThat(d.clientId()).isEqualTo(CLIENT);
        assertThat(d.authAdapterId()).isEqualTo("ADP-K1");
    }

    @Test
    void 任意模式_主体已停用_拒40107() {
        givenClient(client("DISABLED", "ADP-K1", null, null));
        for (String mode : List.of("OFF", "OPTIONAL", "ENFORCED")) {
            ClientAuthVerifier.Decision d = verify(mode, Map.of("X-Client-Id", CLIENT));
            assertThat(d.passed()).as(mode).isFalse();
            assertThat(d.errorCode()).as(mode).isEqualTo(40107);
            assertThat(d.message()).as(mode).contains("已停用");
        }
    }

    // ---------- 反证：不回退 Noop（D-CA-7 / §6.5） ----------

    @Test
    void 反证_未配置鉴权方式_拒40108而非放行() {
        givenClient(client("ENABLED", null, null, null));
        ClientAuthVerifier.Decision d = verify("ENFORCED", Map.of("X-Client-Id", CLIENT));
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40108);
        assertThat(d.message()).contains("未配置鉴权方式");
    }

    @Test
    void 反证_鉴权适配器已停用_拒40108而非回退() {
        givenClient(client("ENABLED", "ADP-K1", null, null));
        when(adapterRepo.findById("ADP-K1"))
                .thenReturn(Optional.of(adapter("ADP-K1", "ClientApiKeyVerifyAdapter", false, null)));
        ClientAuthVerifier.Decision d = verify("ENFORCED",
                Map.of("X-Client-Id", CLIENT, "X-Api-Key", "secret-1234"));
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40108);
        assertThat(d.message()).contains("不可用");
    }

    @Test
    void 无可用凭证_拒40108() {
        givenClient(client("ENABLED", "ADP-K1", null, null));
        when(adapterRepo.findById("ADP-K1"))
                .thenReturn(Optional.of(adapter("ADP-K1", "ClientApiKeyVerifyAdapter", true, null)));
        when(credentialRepo.findVerifiable(any(), anyString(), anyString())).thenReturn(List.of());
        ClientAuthVerifier.Decision d = verify("ENFORCED",
                Map.of("X-Client-Id", CLIENT, "X-Api-Key", "secret-1234"));
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40108);
        assertThat(d.message()).contains("无可用凭证");
    }

    @Test
    void 凭证不匹配_拒40100() {
        givenApiKeyAdapter(true);
        ClientAuthVerifier.Decision d = verify("ENFORCED",
                Map.of("X-Client-Id", CLIENT, "X-Api-Key", "wrong-key"));
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40100);
    }

    // ---------- 步骤 ⓪：预检 / 内部令牌 ----------

    @Test
    void OPTIONS预检_任何模式都放行() {
        for (String mode : List.of("OFF", "OPTIONAL", "ENFORCED")) {
            ClientAuthVerifier.Decision d = verifier(mode)
                    .verify(iface(), "OPTIONS", Map.of(), new byte[0], "1.2.3.4", null, "browser", "t");
            assertThat(d.passed()).as(mode).isTrue();
            assertThat(d.authMethod()).as(mode).isEqualTo("NONE");
        }
    }

    @Test
    void 内部令牌命中_ENFORCED下也放行并记为PLATFORM_SELF() {
        ClientAuthVerifier.Decision d = verifier("ENFORCED")
                .verify(iface(), "POST", Map.of(InternalCallToken.HEADER, INTERNAL), "{}".getBytes(),
                        "127.0.0.1", null, "apicenter", "t");
        assertThat(d.passed()).isTrue();
        assertThat(d.authMethod()).isEqualTo("PLATFORM_SELF");
    }

    @Test
    void 内部令牌为空_不豁免() {
        // 空令牌 ⇒ 不豁免（防"空令牌放行"）→ 仍按 ENFORCED 判：无主体头 ⇒ 40107
        ClientAuthProperties props = new ClientAuthProperties("ENFORCED", null, null, "", null);
        ClientAuthVerifier v = new ClientAuthVerifier(props, new InternalCallToken(props), clientRepo,
                adapterRepo, credentialRepo, cryptoService, new ObjectMapper(),
                Map.of("ClientApiKeyVerifyAdapter", new ClientApiKeyVerifyAdapter()),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), alertService);
        ClientAuthVerifier.Decision d = v.verify(iface(), "POST", Map.of(InternalCallToken.HEADER, ""),
                "{}".getBytes(), null, null, null, "t");
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40107);
    }

    // ---------- 步骤 ②：调用方维度 IP 名单 ----------

    @Test
    void IP黑名单命中_拒40103() {
        givenClient(client("ENABLED", "ADP-K1", null, "1.2.3.4"));
        when(adapterRepo.findById("ADP-K1"))
                .thenReturn(Optional.of(adapter("ADP-K1", "ClientApiKeyVerifyAdapter", true, null)));
        ClientAuthVerifier.Decision d = verify("ENFORCED",
                Map.of("X-Client-Id", CLIENT, "X-Api-Key", "secret-1234"));
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40103);
    }

    @Test
    void IP白名单非空且未命中_拒40103() {
        givenClient(client("ENABLED", "ADP-K1", "10.0.0.1,10.0.0.2", null));
        when(adapterRepo.findById("ADP-K1"))
                .thenReturn(Optional.of(adapter("ADP-K1", "ClientApiKeyVerifyAdapter", true, null)));
        ClientAuthVerifier.Decision d = verify("ENFORCED",
                Map.of("X-Client-Id", CLIENT, "X-Api-Key", "secret-1234"));
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40103);
    }

    @Test
    void 仅IP名单方式_白名单命中即通过且不需要凭证() {
        givenClient(client("ENABLED", "ADP-IP", "1.2.3.4", null));
        when(adapterRepo.findById("ADP-IP"))
                .thenReturn(Optional.of(adapter("ADP-IP", "ClientIpWhitelistVerifyAdapter", true, null)));
        ClientAuthVerifier.Decision d = verify("ENFORCED", Map.of("X-Client-Id", CLIENT));
        assertThat(d.passed()).isTrue();
        assertThat(d.authMethod()).isEqualTo("IP_WHITELIST");
    }

    @Test
    void 仅IP名单方式_白名单为空_拒40103() {
        givenClient(client("ENABLED", "ADP-IP", null, null));
        when(adapterRepo.findById("ADP-IP"))
                .thenReturn(Optional.of(adapter("ADP-IP", "ClientIpWhitelistVerifyAdapter", true, null)));
        ClientAuthVerifier.Decision d = verify("ENFORCED", Map.of("X-Client-Id", CLIENT));
        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40103);
        assertThat(d.message()).contains("未配置白名单");
    }

    // ---------- 审计上下文（B3 落库依据） ----------

    @Test
    void 审计上下文_拒绝也登记且字段齐全() {
        when(clientRepo.findById("UNKNOWN")).thenReturn(Optional.empty());
        verify("ENFORCED", Map.of("X-Client-Id", "UNKNOWN"));

        AccessAuthContext.Entry e = AccessAuthContext.get();
        assertThat(e).isNotNull();
        assertThat(e.direction()).isEqualTo("INBOUND_CALL");
        assertThat(e.principalType()).isEqualTo("CLIENT");
        assertThat(e.principalId()).isEqualTo("UNKNOWN");
        assertThat(e.result()).isEqualTo("REJECT");
        assertThat(e.errorCode()).isEqualTo("40107");
        assertThat(e.clientIp()).isEqualTo("1.2.3.4");
        assertThat(e.userAgent()).isEqualTo("curl/8");
        assertThat(e.interfaceCode()).isEqualTo("IF-CLIENT-01");
        assertThat(e.latencyMs()).isNotNull();
    }

    @Test
    void 审计上下文_通过时含主体名称快照与方式() {
        givenApiKeyAdapter(true);
        verify("ENFORCED", Map.of("X-Client-Id", CLIENT, "X-Api-Key", "secret-1234"));

        AccessAuthContext.Entry e = AccessAuthContext.get();
        assertThat(e.result()).isEqualTo("PASS");
        assertThat(e.principalName()).isEqualTo("ERP 生产");
        assertThat(e.authMethod()).isEqualTo("API_KEY");
        assertThat(e.authAdapterId()).isEqualTo("ADP-K1");
    }
}
