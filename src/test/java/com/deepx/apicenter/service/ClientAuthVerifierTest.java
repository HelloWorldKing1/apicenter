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
import com.deepx.apicenter.repository.InterfaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private final InterfaceRepository interfaceRepo = mock(InterfaceRepository.class);
    /** 平台设置（v1.2）：默认「兼容档」= 强制自报主体 + 无平台默认方式（等价 v1.1 行为） */
    private final InboundAuthSettingService settingService = mock(InboundAuthSettingService.class);

    /** 兼容档（require_client_id=1）：v1.1 行为，既有用例全部据此断言 */
    private ClientAuthVerifier verifier(String mode) {
        return verifier(mode, true, null);
    }

    /** 开放集档（require_client_id=0）：自报主体不参与放行（v1.2 新增用例用） */
    private ClientAuthVerifier openSetVerifier(String mode) {
        return verifier(mode, false, null);
    }

    /** 指标注册表提为字段：v1.2 要断言「自报主体不进标签」（防基数爆炸） */
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private ClientAuthVerifier verifier(String mode, boolean requireClientId, String defaultAdapterId) {
        when(settingService.requireClientId()).thenReturn(requireClientId);
        when(settingService.defaultAdapterId()).thenReturn(defaultAdapterId);
        when(interfaceRepo.findBindings(anyLong())).thenReturn(List.of());
        ClientAuthProperties props = new ClientAuthProperties(mode, null, null, INTERNAL, null, null);
        InternalCallToken token = new InternalCallToken(props);
        Map<String, Adapter> beans = Map.of(
                "ClientApiKeyVerifyAdapter", new ClientApiKeyVerifyAdapter(),
                "ClientHmacVerifyAdapter", new ClientHmacVerifyAdapter(),
                "ClientBearerVerifyAdapter", new ClientBearerVerifyAdapter(),
                "ClientIpWhitelistVerifyAdapter", new ClientIpWhitelistVerifyAdapter());
        return new ClientAuthVerifier(props, token, clientRepo, adapterRepo, credentialRepo, interfaceRepo,
                settingService, cryptoService, new ObjectMapper(), beans, registry, alertService);
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
        when(settingService.requireClientId()).thenReturn(true);
        when(interfaceRepo.findBindings(anyLong())).thenReturn(List.of());
        ClientAuthProperties props = new ClientAuthProperties("ENFORCED", null, null, "", null, null);
        ClientAuthVerifier v = new ClientAuthVerifier(props, new InternalCallToken(props), clientRepo,
                adapterRepo, credentialRepo, interfaceRepo, settingService, cryptoService, new ObjectMapper(),
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

    // ---------- v1.2（C2）：判定中心 = 凭证池 ----------

    /** C4：指标标签必须**有界** —— 开放集下自报主体不可信，随机 id 不能变成新标签 */
    @Test
    void 指标标签_自报未知主体归入unverified_已登记主体才用其id() {
        // ① 未知自报主体（兼容档 ⇒ 40107，但标签不能是那个随机 id）
        when(clientRepo.findById("bogus-123")).thenReturn(Optional.empty());
        verifier("ENFORCED").verify(iface(), "POST", Map.of("X-Client-Id", "bogus-123"),
                "{}".getBytes(), "1.2.3.4", null, "curl/8", "t-tag1");
        assertThat(registry.find("apicenter.gateway.auth").tag("principal", "unverified").counter()).isNotNull();
        assertThat(registry.find("apicenter.gateway.auth").tag("principal", "bogus-123").counter()).isNull();

        // ② 已登记主体 ⇒ 用它的 id（基数 = 登记集，天然有界）
        givenApiKeyAdapter(true);
        verifier("ENFORCED").verify(iface(), "POST",
                Map.of("X-Client-Id", CLIENT, "X-Api-Key", "secret-1234"),
                "{}".getBytes(), "1.2.3.4", null, "curl/8", "t-tag2");
        assertThat(registry.find("apicenter.gateway.auth").tag("principal", CLIENT).counter()).isNotNull();
    }

    /** 用例 39/40：开放集档（require_client_id=0）—— 不带 X-Client-Id 也能凭平台池凭证调用 */
    @Test
    void 开放集_无主体_平台池凭证命中即放行_并记录凭证归因() {
        ClientAuthVerifier v = openSetVerifier("ENFORCED");
        when(adapterRepo.findById("ADP-P1"))
                .thenReturn(Optional.of(adapter("ADP-P1", "ClientApiKeyVerifyAdapter", true, null)));
        when(settingService.defaultAdapterId()).thenReturn("ADP-P1");
        when(credentialRepo.findVerifiable(eq(CredentialOwner.PLATFORM), eq(null), eq("API_KEY")))
                .thenReturn(List.of(new CredentialRow(7, null, "API_KEY", "enc", "ACTIVE",
                        null, null, null, null, "某公司 2026-09-24")));
        when(cryptoService.decrypt("enc")).thenReturn("secret-1234");
        when(cryptoService.fingerprint("secret-1234")).thenReturn("1234");

        ClientAuthVerifier.Decision d = v
                .verify(iface(), "POST", Map.of("X-Api-Key", "secret-1234"), "{}".getBytes(), "1.2.3.4",
                        null, "curl/8", "t-open");

        assertThat(d.passed()).isTrue();
        assertThat(d.authMethod()).isEqualTo("API_KEY");
        assertThat(d.principalType()).isEqualTo("UNVERIFIED");   // 无主体 ⇒ 自报未验证
        assertThat(d.credentialLabel()).isEqualTo("某公司 2026-09-24");
        assertThat(d.credentialFingerprint()).isEqualTo("1234");
        // 审计同样落凭证归因（D-CA-20：共享凭证下唯一不可伪造的抓手）
        assertThat(AccessAuthContext.get().credentialLabel()).isEqualTo("某公司 2026-09-24");
    }

    @Test
    void 开放集_无主体_凭证错误_拒40100而非40107() {
        ClientAuthVerifier v = openSetVerifier("ENFORCED");
        when(adapterRepo.findById("ADP-P1"))
                .thenReturn(Optional.of(adapter("ADP-P1", "ClientApiKeyVerifyAdapter", true, null)));
        when(settingService.defaultAdapterId()).thenReturn("ADP-P1");
        when(credentialRepo.findVerifiable(any(), any(), eq("API_KEY")))
                .thenReturn(List.of(new CredentialRow(7, null, "API_KEY", "enc", "ACTIVE",
                        null, null, null, null, null)));
        when(cryptoService.decrypt("enc")).thenReturn("secret-1234");

        ClientAuthVerifier.Decision d = v
                .verify(iface(), "POST", Map.of("X-Api-Key", "WRONG"), "{}".getBytes(), "1.2.3.4",
                        null, "curl/8", "t-open2");

        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40100);   // 主体缺失不再拦截（v1.2）
    }

    /** 用例 42：池**逐级短路** —— 接口池有可用凭证时，平台池密钥必须被拒（接口隔离语义） */
    @Test
    void 接口池短路_接口池有凭证时平台池密钥被拒_删掉接口池后同一请求变200() {
        ClientAuthVerifier v = openSetVerifier("ENFORCED");
        when(adapterRepo.findById("ADP-P1"))
                .thenReturn(Optional.of(adapter("ADP-P1", "ClientApiKeyVerifyAdapter", true, null)));
        when(settingService.defaultAdapterId()).thenReturn("ADP-P1");
        when(cryptoService.decrypt("encI")).thenReturn("iface-secret");
        when(credentialRepo.findVerifiable(eq(CredentialOwner.INTERFACE), eq("9"), eq("API_KEY")))
                .thenReturn(List.of(new CredentialRow(11, "9", "API_KEY", "encI", "ACTIVE",
                        null, null, null, null, "接口专属")));
        when(credentialRepo.findVerifiable(eq(CredentialOwner.PLATFORM), eq(null), eq("API_KEY")))
                .thenReturn(List.of(new CredentialRow(12, null, "API_KEY", "encP", "ACTIVE",
                        null, null, null, null, "平台共享")));
        when(cryptoService.decrypt("encP")).thenReturn("platform-secret");

        // 接口池有凭证（kind=API_KEY）⇒ 只看这一级 ⇒ 平台池密钥即便正确也被拒
        ClientAuthVerifier.Decision denied = v
                .verify(iface(), "POST", Map.of("X-Api-Key", "platform-secret"), "{}".getBytes(), "1.2.3.4",
                        null, "curl/8", "t-sc1");
        assertThat(denied.passed()).isFalse();
        assertThat(denied.errorCode()).isEqualTo(40100);

        // 接口池密钥则通过
        ClientAuthVerifier.Decision ok = v
                .verify(iface(), "POST", Map.of("X-Api-Key", "iface-secret"), "{}".getBytes(), "1.2.3.4",
                        null, "curl/8", "t-sc2");
        assertThat(ok.passed()).isTrue();
        assertThat(ok.credentialLabel()).isEqualTo("接口专属");

        // 反证：接口池清空（可用凭证不存在）后，同一平台池密钥变 200
        when(credentialRepo.findVerifiable(eq(CredentialOwner.INTERFACE), eq("9"), eq("API_KEY")))
                .thenReturn(List.of());
        ClientAuthVerifier.Decision after = v
                .verify(iface(), "POST", Map.of("X-Api-Key", "platform-secret"), "{}".getBytes(), "1.2.3.4",
                        null, "curl/8", "t-sc3");
        assertThat(after.passed()).isTrue();
        assertThat(after.credentialLabel()).isEqualTo("平台共享");
    }

    /** 用例 43：档案停用 ⇒ 其档案池凭证不参与取值（「停用 = 吊销凭证」语义闭合） */
    @Test
    void 开放集_档案已停用_其档案池凭证不参与取值() {
        ClientAuthVerifier v = openSetVerifier("ENFORCED");
        givenClient(client("DISABLED", "ADP-K1", null, null));
        when(adapterRepo.findById("ADP-K1"))
                .thenReturn(Optional.of(adapter("ADP-K1", "ClientApiKeyVerifyAdapter", true, null)));
        when(credentialRepo.findVerifiable(eq(CredentialOwner.CLIENT), eq(CLIENT), eq("API_KEY")))
                .thenReturn(List.of(new CredentialRow(5, CLIENT, "API_KEY", "enc", "ACTIVE",
                        null, null, null, null, null)));
        when(cryptoService.decrypt("enc")).thenReturn("secret-1234");

        ClientAuthVerifier.Decision d = v
                .verify(iface(), "POST", Map.of("X-Client-Id", CLIENT, "X-Api-Key", "secret-1234"),
                        "{}".getBytes(), "1.2.3.4", null, "curl/8", "t-dis");

        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40108);   // 三级池皆不可用（档案停用 ⇒ 其池不参与）
    }

    /** 用例 45：方式归属 —— 接口绑定 CLIENT_AUTH 覆盖平台默认 */
    @Test
    void 接口绑定CLIENT_AUTH_覆盖平台默认方式() {
        ClientAuthVerifier v = verifier("ENFORCED", false, "ADP-H1");
        when(interfaceRepo.findBindings(9L)).thenReturn(List.of(
                new InterfaceRow.BindingRow(1, "CLIENT_AUTH", "ADP-K1", null)));
        when(adapterRepo.findById("ADP-K1"))
                .thenReturn(Optional.of(adapter("ADP-K1", "ClientApiKeyVerifyAdapter", true, null)));
        when(adapterRepo.findById("ADP-H1"))
                .thenReturn(Optional.of(adapter("ADP-H1", "ClientHmacVerifyAdapter", true, null)));
        when(credentialRepo.findVerifiable(eq(CredentialOwner.PLATFORM), eq(null), eq("API_KEY")))
                .thenReturn(List.of(new CredentialRow(7, null, "API_KEY", "enc", "ACTIVE",
                        null, null, null, null, null)));
        when(cryptoService.decrypt("enc")).thenReturn("secret-1234");

        // 平台默认是 HMAC，但接口绑了 API Key ⇒ 按 API Key 判定
        ClientAuthVerifier.Decision d = v
                .verify(iface(), "POST", Map.of("X-Api-Key", "secret-1234"), "{}".getBytes(), "1.2.3.4",
                        null, "curl/8", "t-bind");

        assertThat(d.passed()).isTrue();
        assertThat(d.authAdapterId()).isEqualTo("ADP-K1");
        assertThat(d.authMethod()).isEqualTo("API_KEY");
    }

    /** 用例 47 的对照：三级池全空 ⇒ 40108（与「密钥不匹配」40100 分开，运维可自助定位） */
    @Test
    void 开放集_池全空_拒40108而非40100() {
        ClientAuthVerifier v = openSetVerifier("ENFORCED");
        when(adapterRepo.findById("ADP-P1"))
                .thenReturn(Optional.of(adapter("ADP-P1", "ClientApiKeyVerifyAdapter", true, null)));
        when(settingService.defaultAdapterId()).thenReturn("ADP-P1");
        when(credentialRepo.findVerifiable(any(), any(), eq("API_KEY"))).thenReturn(List.of());

        ClientAuthVerifier.Decision d = v
                .verify(iface(), "POST", Map.of("X-Api-Key", "whatever"), "{}".getBytes(), "1.2.3.4",
                        null, "curl/8", "t-empty");

        assertThat(d.passed()).isFalse();
        assertThat(d.errorCode()).isEqualTo(40108);
        assertThat(d.message()).contains("无可用凭证");
    }
}
