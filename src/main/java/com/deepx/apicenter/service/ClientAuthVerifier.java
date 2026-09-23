package com.deepx.apicenter.service;

import com.deepx.apicenter.adapter.auth.InboundAuthAdapter;
import com.deepx.apicenter.aspect.AccessAuthContext;
import com.deepx.apicenter.aspect.SensitiveDataMasker;
import com.deepx.apicenter.config.ClientAuthProperties;
import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.ChainPhase;
import com.deepx.apicenter.engine.InboundCredential;
import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdapterRow;
import com.deepx.apicenter.model.ClientAppRow;
import com.deepx.apicenter.model.CredentialRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.ClientAppRepository;
import com.deepx.apicenter.repository.CredentialOwner;
import com.deepx.apicenter.repository.CredentialRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * **调用方鉴权判定**（网关入口闸门）。v1.2（2026-09-24）后**判定中心 = 凭证**，不再是「身份」。
 *
 * <h3>v1.1 → v1.2 的语义变化</h3>
 * v1.1：「先登记调用方（`client_app` + 给它配方式 + 发凭证），才准调」；
 * v1.2：「**持有平台签发的凭证就准调**，登记是可选的精细管控」—— 因为调用方是**未知且持续新增的第三方**（开放集）。
 * 判定中心随之从「主体能否识别」变为「**凭证是否命中池**」。
 *
 * <h3>判定顺序</h3>
 * <pre>
 * ⓪a OPTIONS 预检 → 放行（CORS 预检不带凭证）
 * ⓪b X-Internal-Token 命中 → PLATFORM_SELF 放行（平台自调，D-CA-17；仍写审计）
 * mode=OFF → 一律跳过（真值表第一行；**必须在任何主体/凭证判定之前**，2026-09-23 踩过）
 * ① 自报主体（**不参与放行**）：X-Client-Id → client_app
 *    · require_client_id=1（兼容档）：缺失/未知/停用 ⇒ 40107（OPTIONAL 下"无主体但带凭证"才拒，无凭证放行）
 *    · require_client_id=0（开放集）：一律不拒，只影响审计归属与「档案池凭证是否参与取值」
 * ② 方式解析（**三级回退**）：接口绑定 CLIENT_AUTH → 平台设置 default_adapter_id → 命中档案的 auth_adapter_id
 *    · 都没有 / 适配器不存在·停用·类型不符 ⇒ 40108（fail-closed，**绝不回退 Noop**，§6.5）
 * ③ 档案维度 IP 名单（方式参数名单在适配器内判定；两者 AND 叠加）
 * ④ 凭证注入（**池三级逐级短路**）：INTERFACE(接口) → PLATFORM(平台) → CLIENT(档案)
 *    · 某级有可用凭证（该 kind + ACTIVE/未过期 ROTATING）就**只用这一级**
 *    · 三级全空 ⇒ 40108「平台未配置可用凭证」（与「密钥不匹配」40100 分开，运维可自助定位）
 * ⑤ 适配器校验 → 40100 / 40101；命中时记录 `credential_label` / `credential_fingerprint`（D-CA-20）
 * </pre>
 *
 * <h3>兼容档与开放集</h3>
 * `inbound_auth_setting.require_client_id` 默认 `1`（= v1.1 行为，旧用例与既有部署零变更）；
 * 设为 `0` 才是开放集语义（「放松」动作，需显式改，且会落 WARN 告警）。
 *
 * <p>⚠️ 本类在**热路径**上（每请求一次）：方式解析读接口绑定 + 平台设置（内存缓存）+ 一次凭证池查询，
 * 与 v1.1 的 DB 开销同量级。
 */
@Service
@org.springframework.boot.context.properties.EnableConfigurationProperties(ClientAuthProperties.class)
public class ClientAuthVerifier {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClientAuthVerifier.class);

    /** 判定结果：网关据 `passed` 放行/拒绝（`errorCode`/`message` 直接用于错误响应） */
    public record Decision(boolean passed, int errorCode, String message,
                           String clientId, String principalName, String principalType,
                           String authMethod, String authAdapterId,
                           String credentialLabel, String credentialFingerprint) {

        /** 放行（`errorCode` / `message` 无意义） */
        public static Decision pass(String clientId, String principalName, String principalType,
                                    String authMethod, String authAdapterId,
                                    String credentialLabel, String credentialFingerprint) {
            return new Decision(true, 0, null, clientId, principalName, principalType,
                    authMethod, authAdapterId, credentialLabel, credentialFingerprint);
        }
    }

    /** `OPTIONAL` 下用于判断"是否带了凭证头"的默认头名（自定义头名在未声明主体时无法预知，见类注释） */
    private static final List<String> CREDENTIAL_HEADERS =
            List.of("X-Api-Key", "X-Signature", "Authorization", "X-Auth-Token", "X-Access-Token");

    private static final String PRINCIPAL_CLIENT = "CLIENT";
    private static final String PRINCIPAL_UNVERIFIED = "UNVERIFIED";
    private static final String PRINCIPAL_PLATFORM = "PLATFORM_SELF";

    private final ClientAuthProperties props;
    private final InternalCallToken internalCallToken;
    private final ClientAppRepository clientAppRepository;
    private final AdapterRepository adapterRepository;
    private final CredentialRepository credentialRepository;
    private final InterfaceRepository interfaceRepository;
    private final InboundAuthSettingService settingService;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final Map<String, Adapter> adapterBeans;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final AlertService alertService;

    public ClientAuthVerifier(ClientAuthProperties props, InternalCallToken internalCallToken,
                              ClientAppRepository clientAppRepository, AdapterRepository adapterRepository,
                              CredentialRepository credentialRepository, InterfaceRepository interfaceRepository,
                              InboundAuthSettingService settingService, CryptoService cryptoService,
                              ObjectMapper objectMapper, Map<String, Adapter> adapterBeans,
                              io.micrometer.core.instrument.MeterRegistry meterRegistry,
                              AlertService alertService) {
        this.props = props;
        this.internalCallToken = internalCallToken;
        this.clientAppRepository = clientAppRepository;
        this.adapterRepository = adapterRepository;
        this.credentialRepository = credentialRepository;
        this.interfaceRepository = interfaceRepository;
        this.settingService = settingService;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
        this.adapterBeans = adapterBeans;
        this.meterRegistry = meterRegistry;
        this.alertService = alertService;
    }

    /**
     * 执行一次调用方鉴权判定，并（按配置）登记 {@link AccessAuthContext} 供网关切面落库。
     *
     * @param iface      命中的接口（审计的 interface_id/code 快照 + 接口专属凭证池与绑定解析）
     * @param httpMethod 请求方法（`OPTIONS` 走步骤 ⓪ 豁免）
     * @param headers    入站请求头（大小写不敏感取用见 {@link #header}）
     * @param rawBody    原始报文（HMAC 签名串要用）
     * @param clientIp   调用方来源 IP（可信代理解析口径见设计方案 §11）
     */
    public Decision verify(InterfaceRow iface, String httpMethod, Map<String, String> headers, byte[] rawBody,
                           String clientIp, String xffChain, String userAgent, String traceId) {
        long start = System.nanoTime();
        String mode = props.modeOrDefault();
        String idHeader = props.idHeaderOrDefault();
        String rawClientId = header(headers, idHeader);

        // ⓪ 预检 / 内部调用
        if ("OPTIONS".equalsIgnoreCase(httpMethod)) {
            return settle(iface, traceId, "PASS", null, "CORS 预检豁免", "NONE", null, clientIp, xffChain,
                    userAgent, start, null, null,
                    Decision.pass(null, null, null, "NONE", null, null, null));
        }
        if (internalCallToken.matches(header(headers, InternalCallToken.HEADER))) {
            return settle(iface, traceId, "PASS", null, "平台内部调用（PLATFORM_SELF）", "PLATFORM_SELF", null,
                    clientIp, xffChain, userAgent, start, null, null,
                    Decision.pass(null, "PLATFORM_SELF", PRINCIPAL_PLATFORM, "PLATFORM_SELF", null, null, null));
        }
        // ⚠️ 真值表第一行（§6.2）：`OFF` 下**不校验凭证**（下面第二个 OFF 分支放行）。
        //    但它**放在主体识别之后** —— 保留 v1.1 的不变量「**显式声明的主体必须可识别**」（兼容档）：
        //    OFF + 自称未知主体 ⇒ 40107（能拓配置错误/伪造探测，也是既有用例的预期）。
        //    2026-09-23 全量套件抓到的是另一半：**无主体 + 带凭证** 在 OFF 下必须放行（M4IntegrationTest.c6）。

        // ① 自报主体（v1.2：**不参与放行判定**；只决定审计归属与「档案池凭证是否参与取值」）
        boolean requireClientId = settingService.requireClientId();
        ClientAppRow client = null;
        if (rawClientId != null) {
            Optional<ClientAppRow> found = clientAppRepository.findById(rawClientId);
            if (found.isEmpty()) {
                meterRegistry.counter("apicenter.auth.unknown_principal", "ip",
                        clientIp == null ? "-" : clientIp).increment();
                if (requireClientId) {
                    return reject(iface, traceId, 40107, "鉴权失败：调用方不存在：" + rawClientId,
                            rawClientId, null, null, "NONE", null, clientIp, xffChain, userAgent, start);
                }
                log.debug("自报主体未知（开放集，不拦截）：{}", rawClientId);
            } else if (!"ENABLED".equals(found.get().status())) {
                if (requireClientId) {
                    return reject(iface, traceId, 40107, "鉴权失败：调用方已停用：" + rawClientId,
                            rawClientId, found.get().name(), PRINCIPAL_CLIENT, "NONE", null,
                            clientIp, xffChain, userAgent, start);
                }
                // 开放集：不拦主体，但**其档案池凭证不参与取值**（「停用 = 吊销凭证」语义，§6.5）
                log.debug("自报主体已停用（开放集：仅不取其档案池凭证）：{}", rawClientId);
            } else {
                client = found.get();
            }
        } else if (requireClientId && !"OFF".equals(mode)) {
            // 兼容档（v1.1 行为）：OPTIONAL 下"无主体但有凭证" ⇒ 40107；无凭证 ⇒ 放行观察
            String credentialHeader = firstPresent(headers);
            if ("OPTIONAL".equals(mode) && credentialHeader == null) {
                return settle(iface, traceId, "PASS", null, "OPTIONAL 且未带主体与凭证（观察放行）",
                        "NONE", null, clientIp, xffChain, userAgent, start, null, null,
                        Decision.pass(null, null, null, "NONE", null, null, null));
            }
            if ("OPTIONAL".equals(mode)) {
                return reject(iface, traceId, 40107,
                        "鉴权失败：携带了凭证头 " + credentialHeader + " 但缺少主体标识头 " + idHeader,
                        null, null, null, "NONE", null, clientIp, xffChain, userAgent, start);
            }
            return reject(iface, traceId, 40107, "鉴权失败：缺少主体标识头 " + idHeader,
                    null, null, null, "NONE", null, clientIp, xffChain, userAgent, start);
        }

        // OFF：不校验凭证（仍写审计，供观察期"先看清谁在调"）
        if ("OFF".equals(mode)) {
            return settle(iface, traceId, "PASS", client != null ? client.clientId() : rawClientId,
                    "未启用调用方鉴权（mode=OFF）", "NONE", null, clientIp, xffChain, userAgent, start, null, null,
                    Decision.pass(client != null ? client.clientId() : rawClientId,
                            client == null ? null : client.name(),
                            client != null ? PRINCIPAL_CLIENT : PRINCIPAL_UNVERIFIED,
                            "NONE", null, null, null));
        }

        String principalId = client != null ? client.clientId() : rawClientId;
        String principalName = client != null ? client.name() : null;
        String principalType = client != null ? PRINCIPAL_CLIENT : PRINCIPAL_UNVERIFIED;

        // ② 方式解析（三级回退；fail-closed）
        AdapterRow adapterRow = resolveAdapter(iface, client);
        if (adapterRow == null) {
            // 文案同时覆盖两种成因（未配置 / 配了但不可用），便于联调自助定位
            return reject(iface, traceId, 40108,
                    "鉴权失败：未配置鉴权方式（接口绑定 / 平台默认 / 调用方档案均无），或已配置的适配器不可用（已停用 / 类型不符）",
                    principalId, principalName, principalType, "NONE", null,
                    clientIp, xffChain, userAgent, start);
        }
        Adapter bean = adapterBeans.get(adapterRow.impl());
        if (!(bean instanceof InboundAuthAdapter inbound)) {
            return reject(iface, traceId, 40108, "鉴权失败：适配器实现不支持入站鉴权：" + adapterRow.impl(),
                    principalId, principalName, principalType, "NONE", adapterRow.id(),
                    clientIp, xffChain, userAgent, start);
        }
        String method = inbound.method();
        String credentialKind = inbound.credentialKind();

        // ③ 档案维度 IP 名单（方式参数名单在适配器内判定）
        //    ⚠️ 传给适配器的 `clientIpAllowed` 只在**档案确实配了非空白名单且命中**时为 TRUE：
        //       档案「没配名单」= 不限（此处不拦），但**不能**当作「IP 方式的白名单」——
        //       仅 IP 名单方式要求白名单非空（D-CA-7），该判定在适配器里（可来自 params 或档案）
        Boolean clientIpAllowed = null;
        if (client != null) {
            if (!ipAllowed(client, clientIp)) {
                return reject(iface, traceId, 40103, "鉴权失败：调用方来源 IP 被拒（" + clientIp + "）",
                        principalId, principalName, principalType, method, adapterRow.id(),
                        clientIp, xffChain, userAgent, start);
            }
            if (!isBlank(client.ipWhitelist())) {
                clientIpAllowed = Boolean.TRUE;
            }
        }

        // OPTIONAL 且未带任何凭证头 ⇒ 放行观察（真值表：OPTIONAL + 无凭证）
        //    例外（兼容档语义）：**声明了主体（且可识别）却未带凭证** ⇒ 40100（配置错误，不能静默放行 —— v1.1 行为）
        if ("OPTIONAL".equals(mode) && credentialKind != null && firstPresent(headers) == null) {
            if (client != null) {
                return reject(iface, traceId, 40100,
                        "鉴权失败：声明了主体（" + client.clientId() + "）但未携带凭证",
                        principalId, principalName, principalType, method, adapterRow.id(),
                        clientIp, xffChain, userAgent, start);
            }
            return settle(iface, traceId, "PASS", principalId, "OPTIONAL 且未带凭证（观察放行）",
                    "NONE", adapterRow.id(), clientIp, xffChain, userAgent, start, null, null,
                    Decision.pass(principalId, principalName, principalType, "NONE", adapterRow.id(), null, null));
        }

        // ④ 凭证注入（池三级逐级短路；仅需要凭证的方式才取）
        List<InboundCredential> candidates = List.of();
        if (credentialKind != null) {
            Level chosen = resolveCredentialLevel(iface, client, credentialKind);
            if (chosen == null) {
                return reject(iface, traceId, 40108,
                        "鉴权失败：无可用凭证（平台未配置该类型的入站凭证，kind=" + credentialKind + "）",
                        principalId, principalName, principalType, method, adapterRow.id(),
                        clientIp, xffChain, userAgent, start);
            }
            candidates = chosen.credentials();
        }

        // ⑤ 适配器校验
        AdapterContext ctx = buildContext(iface, adapterRow, headers, rawBody, candidates, clientIpAllowed,
                clientIp, traceId);
        try {
            inbound.process(ctx);
        } catch (BizException e) {
            return reject(iface, traceId, e.getCode(), e.getMessage(),
                    principalId, principalName, principalType, method, adapterRow.id(),
                    clientIp, xffChain, userAgent, start);
        }
        String label = attr(ctx, "matchedCredentialLabel");
        String fingerprint = attr(ctx, "matchedCredentialFingerprint");
        return settle(iface, traceId, "PASS", principalId, "鉴权通过", method, adapterRow.id(),
                clientIp, xffChain, userAgent, start, label, fingerprint,
                Decision.pass(principalId, principalName, principalType, method, adapterRow.id(), label, fingerprint));
    }

    // ---------- 方式解析与凭证池 ----------

    /**
     * 方式解析（**三级回退**）：① 接口绑定 `CLIENT_AUTH`（v1.2 首选；接口级覆盖，改即生效）
     * → ② 平台设置 `default_adapter_id`（页面可改、即时生效）→ ③ 命中档案的 `auth_adapter_id`（v1.1 兼容路径）。
     *
     * <p>返回 `null` = 三级皆无 ⇒ 40108（**fail-closed，绝不回退 Noop**，§6.5）。
     */
    private AdapterRow resolveAdapter(InterfaceRow iface, ClientAppRow client) {
        List<String> candidates = new ArrayList<>();
        candidates.add(clientAuthBindingAdapterId(iface.id()));
        candidates.add(settingService.defaultAdapterId());
        candidates.add(client == null ? null : client.authAdapterId());
        for (String id : candidates) {
            if (id == null || id.isBlank()) {
                continue;
            }
            AdapterRow row = adapterRepository.findById(id).orElse(null);
            if (row != null && row.enabled() && "auth".equals(row.type())) {
                return row;
            }
        }
        return null;
    }

    /** 接口绑定的 `CLIENT_AUTH` 角色（无绑定返回 null） */
    private String clientAuthBindingAdapterId(long interfaceId) {
        for (InterfaceRow.BindingRow b : interfaceRepository.findBindings(interfaceId)) {
            if ("CLIENT_AUTH".equals(b.role()) && b.adapterId() != null && !b.adapterId().isBlank()) {
                return b.adapterId();
            }
        }
        return null;
    }

    /**
     * 凭证池取值（**逐级短路**）：`INTERFACE(接口 id)` → `PLATFORM(NULL)` → `CLIENT(档案，且档案启用)`。
     *
     * <p>短路依据是「**该级是否有可用凭证**」而非「是否命中」：接口池有凭证但请求带的是平台池密钥 ⇒
     * 由适配器判 40100（**不回落到平台池**）—— 这正是「接口专属池」的隔离语义。
     * 若实现成并集，接口将无法表达「只认本接口的密钥」（设计方案 v1.2 §6.1 ⭐）。
     */
    private Level resolveCredentialLevel(InterfaceRow iface, ClientAppRow client, String kind) {
        List<Level> levels = new ArrayList<>();
        levels.add(new Level(CredentialOwner.INTERFACE, String.valueOf(iface.id())));
        levels.add(new Level(CredentialOwner.PLATFORM, null));
        if (client != null) {
            levels.add(new Level(CredentialOwner.CLIENT, client.clientId()));
        }
        for (Level level : levels) {
            List<InboundCredential> creds = decryptVerifiable(level.owner(), level.ownerId(), kind);
            if (!creds.isEmpty()) {
                return new Level(level.owner(), level.ownerId(), creds);
            }
        }
        return null;
    }

    /** 解密某级可用凭证（单条失败跳过，不影响其他；全失败即视为该级不可用） */
    private List<InboundCredential> decryptVerifiable(CredentialOwner owner, String ownerId, String kind) {
        List<InboundCredential> out = new ArrayList<>();
        for (CredentialRow row : credentialRepository.findVerifiable(owner, ownerId, kind)) {
            try {
                String plaintext = cryptoService.decrypt(row.credential());
                out.add(new InboundCredential(row.id(), plaintext, row.label(),
                        cryptoService.fingerprint(plaintext)));
            } catch (Exception e) {
                // 与回调验签同口径：单条解密失败不整次失败（可能是密钥轮换导致旧密文不可解）
            }
        }
        return out;
    }

    /** 池的一级（owner + ownerId + 已解密候选） */
    private record Level(CredentialOwner owner, String ownerId, List<InboundCredential> credentials) {
        Level(CredentialOwner owner, String ownerId) {
            this(owner, ownerId, List.of());
        }
    }

    // ---------- 私有 ----------

    private AdapterContext buildContext(InterfaceRow iface, AdapterRow adapterRow, Map<String, String> headers,
                                        byte[] rawBody, List<InboundCredential> candidates, Boolean ipAllowed,
                                        String clientIp, String traceId) {
        AdapterContext ctx = AdapterContext.create(ChainPhase.INBOUND_AUTH, UnifiedModel.emptyObject(),
                AdapterContext.InterfaceMeta.of(iface),
                new AdapterContext.AppMeta(null, null),          // 调用方方向没有"应用"概念
                new AdapterContext.TraceMeta(traceId),
                AdapterContext.AuthResult.pass(null), null);
        JsonNode p = params(adapterRow);
        ctx.attrs().put("adapterParams", p);
        ctx.attrs().put("headers", headers == null ? Map.of() : headers);
        ctx.attrs().put("rawBody", rawBody == null ? new byte[0] : rawBody);
        ctx.attrs().put("inboundCredentials", candidates);
        ctx.attrs().put("clientIpAllowed", ipAllowed);
        ctx.attrs().put("clientIp", clientIp);
        registerMaskHeaders(p);
        return ctx;
    }

    private JsonNode params(AdapterRow row) {
        try {
            return row.params() == null || row.params().isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(row.params());
        } catch (Exception e) {
            throw BizException.fieldInvalid("适配器参数非法：" + row.id());
        }
    }

    /** 动态注册需脱敏的头名（D-CA-13 硬要求：可配头名不注册 ⇒ 密钥明文落 call_log.req_headers） */
    private void registerMaskHeaders(JsonNode params) {
        if (params != null) {
            for (String key : List.of("credentialHeaderName", "signatureHeader", "timestampHeader", "headerName")) {
                if (params.has(key) && !params.get(key).isNull()) {
                    SensitiveDataMasker.registerHeader(params.get(key).asText());
                }
            }
        }
        for (String extra : props.maskHeaderNamesOrDefault()) {
            SensitiveDataMasker.registerHeader(extra);
        }
    }

    /** 档案维度 IP 名单：黑名单优先；白名单非空时必须命中（与 GatewayGuard 同口径） */
    private boolean ipAllowed(ClientAppRow client, String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return false;
        }
        if (contains(client.ipBlacklist(), clientIp)) {
            return false;
        }
        String whitelist = client.ipWhitelist();
        if (isBlank(whitelist)) {
            return true;
        }
        return contains(whitelist, clientIp);
    }

    private static boolean contains(String list, String ip) {
        if (isBlank(list)) {
            return false;
        }
        for (String part : split(list)) {
            if (part.equals(ip)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> split(String list) {
        List<String> out = new ArrayList<>();
        for (String part : list.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    /** 大小写不敏感取头值（缺失返回 null） */
    private static String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        String v = headers.get(name);
        if (v != null) {
            return v;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String attr(AdapterContext ctx, String key) {
        Object v = ctx.attrs().get(key);
        return v instanceof String s ? s : null;
    }

    /** 是否带了（默认清单里的）凭证头 —— 仅用于 OPTIONAL 的"带了就必须对"判定 */
    private static String firstPresent(Map<String, String> headers) {
        for (String name : CREDENTIAL_HEADERS) {
            String v = header(headers, name);
            if (v != null && !v.isBlank()) {
                return name;
            }
        }
        return null;
    }

    private Decision reject(InterfaceRow iface, String traceId, int code, String msg,
                            String clientId, String principalName, String principalType,
                            String method, String adapterId, String clientIp, String xffChain,
                            String userAgent, long start) {
        return settle(iface, traceId, "REJECT", clientId, msg, method, adapterId, clientIp, xffChain,
                userAgent, start, null, null,
                new Decision(false, code, msg, clientId, principalName, principalType, method, adapterId,
                        null, null));
    }

    /** 统一收口：登记审计上下文（网关切面 flush）+ 指标 + 告警 + 结构化日志 + 返回结论 */
    private Decision settle(InterfaceRow iface, String traceId, String result, String clientId, String reason,
                            String method, String adapterId, String clientIp, String xffChain, String userAgent,
                            long start, String label, String fingerprint, Decision decision) {
        // ⚠️ 指标标签必须**有界**：v1.2 起主体可自报（开放集），若把自报值打成标签，
        //    伪造者可用随机 id 让 Micrometer 无限建标签（内存/报表爆炸）。
        //    口径：只有**已登记主体**（principalType=CLIENT）才用其 id，其余一律 "unverified"。
        String principalTag = decision != null && "CLIENT".equals(decision.principalType())
                && clientId != null && !clientId.isBlank()
                ? clientId
                : (clientId == null || clientId.isBlank() ? "-" : "unverified");
        String methodTag = method == null || method.isBlank() ? "NONE" : method;
        meterRegistry.counter("apicenter.gateway.auth", "direction", "INBOUND_CALL", "principal", principalTag,
                "method", methodTag, "result", decision.passed() ? "pass" : "reject").increment();
        meterRegistry.timer("apicenter.gateway.auth.latency", "direction", "INBOUND_CALL", "method", methodTag)
                .record(java.time.Duration.ofNanos(System.nanoTime() - start));
        if (!decision.passed()) {
            alertService.recordAuthFailure(clientId, clientIp, decision.principalName(),
                    String.valueOf(decision.errorCode()));
        }
        // 结构化单行日志（§13.3；**不打凭证/签名**，只打结论 + 归因标签）
        log.info("鉴权结论 result={} principal={} ip={} method={} code={} credential={} reason={}",
                result, principalTag, clientIp == null ? "-" : clientIp, methodTag,
                decision.passed() ? 0 : decision.errorCode(), label == null ? "-" : label, reason);
        if (props.auditEnabled() && ("REJECT".equals(result) || props.recordPass())) {
            AccessAuthContext.set(new AccessAuthContext.Entry(
                    traceId, "INBOUND_CALL",
                    decision.principalType() == null
                            ? (clientId == null ? "UNVERIFIED" : "CLIENT") : decision.principalType(),
                    clientId, decision.principalName(),
                    iface == null ? null : iface.id(),
                    iface == null ? null : iface.code(),
                    methodTag, adapterId, result,
                    decision.passed() ? null : String.valueOf(decision.errorCode()),
                    reason, clientIp, xffChain, truncate(userAgent, 200),
                    label, fingerprint, (System.nanoTime() - start) / 1_000_000));
        }
        return decision;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
