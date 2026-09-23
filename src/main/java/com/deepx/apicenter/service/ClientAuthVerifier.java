package com.deepx.apicenter.service;

import com.deepx.apicenter.adapter.auth.InboundAuthAdapter;
import com.deepx.apicenter.aspect.AccessAuthContext;
import com.deepx.apicenter.aspect.SensitiveDataMasker;
import com.deepx.apicenter.config.ClientAuthProperties;
import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.ChainPhase;
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
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * **调用方鉴权闸门内核**（2026-09-23，入站鉴权 B2；设计方案 §6.1 判定顺序 + §6.2 模式真值表）。
 *
 * <p>**本类不接 HTTP**：B3 在 `GatewayController` 里调用它并据结论放行/拒绝；本类只做判定 + 登记审计上下文。
 * 只作用于**出站中转接口**（`if_type=OUTBOUND`）的入站方向；供应商回调验签仍在链内（D-CA-3）。
 *
 * <p>判定顺序（照 §6.1）：
 * <pre>
 * ⓪ OPTIONS → 放行（CORS 预检不带凭证）；内部令牌命中 → PLATFORM_SELF 放行（D-CA-17）
 * ① 主体识别 X-Client-Id（可配头名）→ client_app：不存在 / 停用 → 40107
 * ② IP 名单（调用方维度）：黑名单命中 → 40103；白名单非空且未命中 → 40103
 * ③ 方式解析 client.auth_adapter_id → adapter(type=auth, enabled)：未配 / 停用 → 40108（**不回退 Noop**）
 * ④ 凭证注入 findVerifiable(client_id, kind) → 解密：空 → 40108
 * ⑤ 适配器校验 adapter.process(ctx) → 通过 / 40100 / 40101
 * </pre>
 *
 * <p>**模式真值表要点**（§6.2）：`OFF` 一律不校验（但仍写审计，供观察期）；`OPTIONAL` 只在"声明了主体"时严格；
 * `ENFORCED` 必须通过。**无论何种模式**：**显式声明的主体必须可识别**（未知/停用 → 40107）。
 * 另：`OPTIONAL` 下"带了凭证头却没声明主体" → 40107（配置错误或伪造，不能静默忽略）。
 *
 * <p>适配器侧遵守 D-CA-6：**适配器不查库**，凭证明文由本类注入 `ctx.attrs("inboundCredentials")`。
 */
@Service
@org.springframework.boot.context.properties.EnableConfigurationProperties(ClientAuthProperties.class)
public class ClientAuthVerifier {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClientAuthVerifier.class);

    /** 判定结果：网关据 `passed` 放行/拒绝（`errorCode`/`message` 直接用于错误响应） */
    public record Decision(boolean passed, int errorCode, String message,
                           String clientId, String principalName,
                           String authMethod, String authAdapterId) {

        /** 放行（`errorCode` / `message` 无意义） */
        public static Decision pass(String clientId, String principalName, String authMethod, String authAdapterId) {
            return new Decision(true, 0, null, clientId, principalName, authMethod, authAdapterId);
        }
    }

    /** `OPTIONAL` 下用于判断"是否带了凭证头"的默认头名（自定义头名在未声明主体时无法预知，见类注释） */
    private static final List<String> CREDENTIAL_HEADERS =
            List.of("X-Api-Key", "X-Signature", "Authorization", "X-Auth-Token", "X-Access-Token");

    private final ClientAuthProperties props;
    private final InternalCallToken internalCallToken;
    private final ClientAppRepository clientAppRepository;
    private final AdapterRepository adapterRepository;
    private final CredentialRepository credentialRepository;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final Map<String, Adapter> adapterBeans;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final AlertService alertService;

    public ClientAuthVerifier(ClientAuthProperties props, InternalCallToken internalCallToken,
                              ClientAppRepository clientAppRepository, AdapterRepository adapterRepository,
                              CredentialRepository credentialRepository, CryptoService cryptoService,
                              ObjectMapper objectMapper, Map<String, Adapter> adapterBeans,
                              io.micrometer.core.instrument.MeterRegistry meterRegistry,
                              AlertService alertService) {
        this.props = props;
        this.internalCallToken = internalCallToken;
        this.clientAppRepository = clientAppRepository;
        this.adapterRepository = adapterRepository;
        this.credentialRepository = credentialRepository;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
        this.adapterBeans = adapterBeans;
        this.meterRegistry = meterRegistry;
        this.alertService = alertService;
    }

    /**
     * 执行一次调用方鉴权判定，并（按配置）登记 {@link AccessAuthContext} 供 B3 的网关切面落库。
     *
     * @param iface      命中的接口（用于审计的 interface_id / code 快照）
     * @param httpMethod 请求方法（`OPTIONS` 走步骤 ⓪ 豁免）
     * @param headers    入站请求头（大小写不敏感取用见 {@link #header}）
     * @param rawBody    原始报文（HMAC 签名串要用）
     * @param clientIp   调用方来源 IP（可信代理解析口径见设计方案 §11，由 B3 传入）
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
                    userAgent, start, Decision.pass(null, null, "NONE", null));
        }
        if (internalCallToken.matches(header(headers, InternalCallToken.HEADER))) {
            return settle(iface, traceId, "PASS", null, "平台内部调用（PLATFORM_SELF）", "PLATFORM_SELF", null,
                    clientIp, xffChain, userAgent, start,
                    Decision.pass(null, "PLATFORM_SELF", "PLATFORM_SELF", null));
        }

        // ① 主体识别
        if (rawClientId == null) {
            // ⚠️ 真值表第一行（§6.2）：**`OFF` 下"任意 × 任意"一律跳过鉴权**（不校验、不报错）
            //    ⇒ "带了凭证却没声明主体 → 40107" 这条**只对 OPTIONAL 生效**（ENFORCED 由下面那条覆盖）。
            //    2026-09-23 全量套件抓到：原实现把这条放到了 mode 判断之前 ⇒ `OFF` 下也会拒，
            //    把"调用方自带 Authorization 头但平台未启用鉴权"的既有调用打成 401（M4IntegrationTest.c6）。
            if ("OPTIONAL".equals(mode)) {
                String credentialHeader = firstPresent(headers);
                if (credentialHeader != null) {
                    // 带了凭证却声明不出主体 = 配置错误或伪造，不能静默忽略（§6.2 OPTIONAL 行）
                    return reject(iface, traceId, 40107,
                            "鉴权失败：携带了凭证头 " + credentialHeader + " 但缺少主体标识头 " + idHeader,
                            null, null, "NONE", null, clientIp, xffChain, userAgent, start);
                }
            }
            if ("ENFORCED".equals(mode)) {
                return reject(iface, traceId, 40107,
                        "鉴权失败：缺少主体标识头 " + idHeader, null, null, "NONE", null,
                        clientIp, xffChain, userAgent, start);
            }
            // OFF（放行，且**不看任何凭证头**）/ OPTIONAL 未带主体 → 放行
            return settle(iface, traceId, "PASS", null,
                    "OFF/OPTIONAL 且未带主体（mode=" + mode + "）", "NONE", null,
                    clientIp, xffChain, userAgent, start, Decision.pass(null, null, "NONE", null));
        }

        Optional<ClientAppRow> found = clientAppRepository.findById(rawClientId);
        if (found.isEmpty()) {
            // 未知主体探测指标（§13.1；P2 用于封禁决策）
            meterRegistry.counter("apicenter.auth.unknown_principal", "ip",
                    clientIp == null ? "-" : clientIp).increment();
            // 显式声明的主体必须可识别（与 mode 无关）
            return reject(iface, traceId, 40107, "鉴权失败：调用方不存在：" + rawClientId,
                    rawClientId, null, "NONE", null, clientIp, xffChain, userAgent, start);
        }
        ClientAppRow client = found.get();
        if (!"ENABLED".equals(client.status())) {
            return reject(iface, traceId, 40107, "鉴权失败：调用方已停用：" + rawClientId,
                    client.clientId(), client.name(), "NONE", null, clientIp, xffChain, userAgent, start);
        }
        if ("OFF".equals(mode)) {
            // OFF：不校验（仍写审计，供观察期"先看清谁在调"）
            return settle(iface, traceId, "PASS", client.clientId(),
                    "未启用调用方鉴权（mode=OFF）", "NONE", null, clientIp, xffChain, userAgent, start,
                    Decision.pass(client.clientId(), client.name(), "NONE", null));
        }

        // ③ 方式解析（先于凭证判断：方式决定"是否需要凭证"，IP 名单方式不需要）
        AdapterRow adapterRow = client.authAdapterId() == null ? null
                : adapterRepository.findById(client.authAdapterId()).orElse(null);
        if (adapterRow == null || !adapterRow.enabled() || !"auth".equals(adapterRow.type())) {
            // fail-closed：**不回退 Noop**（与链内「逐层回退」语义相反，§6.5）
            return reject(iface, traceId, 40108,
                    adapterRow == null
                            ? "鉴权失败：调用方未配置鉴权方式（" + client.clientId() + "）"
                            : "鉴权失败：鉴权方式不可用（已停用或类型不符）：" + client.authAdapterId(),
                    client.clientId(), client.name(), "NONE", client.authAdapterId(),
                    clientIp, xffChain, userAgent, start);
        }
        Adapter bean = adapterBeans.get(adapterRow.impl());
        if (!(bean instanceof InboundAuthAdapter inbound)) {
            return reject(iface, traceId, 40108,
                    "鉴权失败：适配器实现不支持入站鉴权：" + adapterRow.impl(),
                    client.clientId(), client.name(), "NONE", adapterRow.id(),
                    clientIp, xffChain, userAgent, start);
        }
        String method = inbound.method();

        // OPTIONAL：声明了主体却没带凭证 → 40100（带了就严格校验）
        String credentialKind = inbound.credentialKind();
        if (credentialKind != null && "OPTIONAL".equals(mode) && firstPresent(headers) == null) {
            return reject(iface, traceId, 40100,
                    "鉴权失败：声明了主体（" + client.clientId() + "）但未携带凭证",
                    client.clientId(), client.name(), method, adapterRow.id(),
                    clientIp, xffChain, userAgent, start);
        }

        // ② IP 名单（调用方维度）
        boolean ipAllowed = ipAllowed(client, clientIp);
        if (!ipAllowed) {
            return reject(iface, traceId, 40103,
                    "鉴权失败：调用方来源 IP 被拒（" + clientIp + "）",
                    client.clientId(), client.name(), method, adapterRow.id(),
                    clientIp, xffChain, userAgent, start);
        }
        // IP 名单方式要求白名单非空（D-CA-7：无名单 + 无凭证 → 一律拒）
        if (credentialKind == null && isBlank(client.ipWhitelist())) {
            return reject(iface, traceId, 40103,
                    "鉴权失败：方式为仅 IP 名单，但调用方未配置白名单",
                    client.clientId(), client.name(), method, adapterRow.id(),
                    clientIp, xffChain, userAgent, start);
        }

        // ④ 凭证注入（解密后只放在上下文里，适配器不查库）
        List<String> plaintexts = new ArrayList<>();
        if (credentialKind != null) {
            for (CredentialRow row : credentialRepository.findVerifiable(CredentialOwner.CLIENT,
                    client.clientId(), credentialKind)) {
                try {
                    plaintexts.add(cryptoService.decrypt(row.credential()));
                } catch (Exception e) {
                    // 单条解密失败不影响其他凭证（与回调验签同口径）
                }
            }
            if (plaintexts.isEmpty()) {
                return reject(iface, traceId, 40108,
                        "鉴权失败：调用方无可用凭证（kind=" + credentialKind + "）",
                        client.clientId(), client.name(), method, adapterRow.id(),
                        clientIp, xffChain, userAgent, start);
            }
        }

        // ⑤ 适配器校验
        AdapterContext ctx = buildContext(iface, adapterRow, headers, rawBody, plaintexts, ipAllowed, traceId);
        try {
            inbound.process(ctx);
        } catch (BizException e) {
            return reject(iface, traceId, e.getCode(), e.getMessage(),
                    client.clientId(), client.name(), method, adapterRow.id(),
                    clientIp, xffChain, userAgent, start);
        }
        return settle(iface, traceId, "PASS", client.clientId(), client.name(), method, adapterRow.id(),
                clientIp, xffChain, userAgent, start,
                Decision.pass(client.clientId(), client.name(), method, adapterRow.id()));
    }

    // ---------- 私有 ----------

    private AdapterContext buildContext(InterfaceRow iface, AdapterRow adapterRow, Map<String, String> headers,
                                        byte[] rawBody, List<String> plaintexts, boolean ipAllowed, String traceId) {
        AdapterContext ctx = AdapterContext.create(ChainPhase.INBOUND_AUTH, UnifiedModel.emptyObject(),
                AdapterContext.InterfaceMeta.of(iface),
                new AdapterContext.AppMeta(null, null),          // 调用方方向没有"应用"概念
                new AdapterContext.TraceMeta(traceId),
                AdapterContext.AuthResult.pass(null), null);
        ctx.attrs().put("adapterParams", params(adapterRow));
        ctx.attrs().put("headers", headers == null ? Map.of() : headers);
        ctx.attrs().put("rawBody", rawBody == null ? new byte[0] : rawBody);
        ctx.attrs().put("inboundCredentials", plaintexts);
        ctx.attrs().put("clientIpAllowed", ipAllowed);
        // 动态注册需脱敏的头名（凭证头/签名头/时间戳头都可能被配置改名，D-CA-13 硬要求）
        JsonNode p = params(adapterRow);
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

    private void registerMaskHeaders(JsonNode params) {
        if (params == null) {
            return;
        }
        for (String key : List.of("credentialHeaderName", "signatureHeader", "timestampHeader", "headerName")) {
            if (params.has(key) && !params.get(key).isNull()) {
                SensitiveDataMasker.registerHeader(params.get(key).asText());
            }
        }
    }

    /** 调用方维度 IP 名单：黑名单优先；白名单非空时必须命中（与 GatewayGuard 同口径） */
    private boolean ipAllowed(ClientAppRow client, String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return isBlank(client.ipWhitelist()) && isBlank(client.ipBlacklist());
        }
        if (contains(client.ipBlacklist(), clientIp)) {
            return false;
        }
        return isBlank(client.ipWhitelist()) || contains(client.ipWhitelist(), clientIp);
    }

    private static boolean contains(String list, String ip) {
        return split(list).contains(ip);
    }

    private static List<String> split(String list) {
        return isBlank(list) ? List.of() : Arrays.stream(list.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }

    /** 头名大小写不敏感取值；缺失返回 null（区别于"空串"） */
    private static String header(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty() || name == null) {
            return null;
        }
        String v = headers.get(name);
        if (v == null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    v = e.getValue();
                    break;
                }
            }
        }
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** OPTIONAL 下判断"是否带了凭证头"（自定义头名在未声明主体时无法预知，属已知取舍） */
    private static String firstPresent(Map<String, String> headers) {
        for (String h : CREDENTIAL_HEADERS) {
            if (header(headers, h) != null) {
                return h;
            }
        }
        return null;
    }

    private Decision reject(InterfaceRow iface, String traceId, int code, String msg,
                            String clientId, String clientName, String method, String adapterId,
                            String clientIp, String xffChain, String userAgent, long start) {
        String errCode = String.valueOf(code);
        settle(iface, traceId, "REJECT", clientId, msg, method, adapterId, clientIp, xffChain, userAgent, start,
                new Decision(false, code, msg, clientId, clientName, method, adapterId));
        return new Decision(false, code, msg, clientId, clientName, method, adapterId);
    }

    /** 统一收口：登记审计上下文（B3 的网关切面 flush）+ 返回结论 */
    private Decision settle(InterfaceRow iface, String traceId, String result, String clientId, String reason,
                            String method, String adapterId, String clientIp, String xffChain, String userAgent,
                            long start, Decision decision) {
        // 指标（设计方案 §13.1：命名沿用 apicenter.*；principal 维度用 "-" 占位避免高基数标签名缺失）
        String principalTag = clientId == null || clientId.isBlank() ? "-" : clientId;
        String methodTag = method == null || method.isBlank() ? "NONE" : method;
        meterRegistry.counter("apicenter.gateway.auth", "direction", "INBOUND_CALL", "principal", principalTag,
                "method", methodTag, "result", decision.passed() ? "pass" : "reject").increment();
        meterRegistry.timer("apicenter.gateway.auth.latency", "direction", "INBOUND_CALL", "method", methodTag)
                .record(java.time.Duration.ofNanos(System.nanoTime() - start));
        if (!decision.passed()) {
            // 连续失败告警（§13.2：按主体；主体未知时按 IP）—— 阈值默认 10 / 5 分钟窗口
            alertService.recordAuthFailure(clientId, clientIp, decision.principalName(),
                    String.valueOf(decision.errorCode()));
        }
        // 结构化单行日志（§13.3；**不打凭证/签名**，只打结论与主体）
        log.info("鉴权结论 result={} principal={} ip={} method={} code={} reason={}",
                result, principalTag, clientIp == null ? "-" : clientIp, methodTag,
                decision.passed() ? 0 : decision.errorCode(), reason);
        if (props.auditEnabled() && ("REJECT".equals(result) || props.recordPass())) {
            AccessAuthContext.set(new AccessAuthContext.Entry(
                    traceId, "INBOUND_CALL", "CLIENT",
                    clientId, decision.principalName(),
                    iface == null ? null : iface.id(),
                    iface == null ? null : iface.code(),
                    method == null ? "NONE" : method,
                    adapterId, result,
                    decision.passed() ? null : String.valueOf(decision.errorCode()),
                    reason, clientIp, xffChain, truncate(userAgent, 200),
                    (System.nanoTime() - start) / 1_000_000));
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
