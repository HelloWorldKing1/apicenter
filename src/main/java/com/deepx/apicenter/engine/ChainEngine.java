package com.deepx.apicenter.engine;

import com.deepx.apicenter.config.ConfigChangedEvent;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.mapping.MappingEngine;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import com.deepx.apicenter.model.AdapterRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.deepx.apicenter.model.AppRow;
import com.deepx.apicenter.model.CredentialRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.CredentialRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.service.CryptoService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 适配器链引擎（M0-01 契约落地 + M5 D-M5-2 解析时机上移）：
 * - 固定六阶段链（§4）：入站鉴权 → 协议解码 → 报文适配 → 字段映射（固定步骤）→ 协议编码 → 出站鉴权
 * - 绑定解析（M0-01 §5 / M5 矩阵 #1-#4）：MESSAGE/AUTH/CALLBACK_AUTH 角色按
 *   「接口覆盖 → 应用默认 → 平台默认」逐层解析，含 binding.version 灰度路由 + enabled 校验 + 逐层回退；
 *   协议按 protocol_in/out 自动推导；回调验签 CALLBACK_AUTH 仅 INBOUND 生效
 * - 解析时机上移（M5 二轮定稿，2026-09-07）：绑定解析结果 / 映射规则 / 入站参数声明在
 *   **链装配时一次解析并烘焙进缓存链**（不再每请求实时查库）；凭证保持每请求实时读
 *   （M0-04 §3.2：轮换即时生效，明文不进缓存）
 * - 链缓存（§7）：key=interface_id，TTL 5 分钟兜底 + ConfigChangedEvent 事件失效（M5 补齐）
 * - 链失败不污染状态机（§6）：解码/映射/编码/验签失败直接 BizException，不落运行表
 */
@Component
public class ChainEngine {

    private static final Logger log = LoggerFactory.getLogger(ChainEngine.class);

    private final InterfaceRepository interfaceRepository;
    private final AppRepository appRepository;
    private final AdapterRepository adapterRepository;
    private final CredentialRepository credentialRepository;
    private final CryptoService cryptoService;
    private final MappingEngine mappingEngine;
    private final Map<String, Adapter> adapterBeans;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<Long, CachedChain> chainCache = new ConcurrentHashMap<>();

    public ChainEngine(InterfaceRepository interfaceRepository,
                       AppRepository appRepository,
                       AdapterRepository adapterRepository,
                       CredentialRepository credentialRepository,
                       CryptoService cryptoService,
                       MappingEngine mappingEngine,
                       List<Adapter> adapters,
                       ObjectMapper objectMapper,
                       ObservationRegistry observationRegistry,
                       ApplicationEventPublisher eventPublisher) {
        this.interfaceRepository = interfaceRepository;
        this.appRepository = appRepository;
        this.adapterRepository = adapterRepository;
        this.credentialRepository = credentialRepository;
        this.cryptoService = cryptoService;
        this.mappingEngine = mappingEngine;
        // impl 名 → Bean（M0-01 §8：Spring Bean 按 impl 名注册）
        this.adapterBeans = adapters.stream()
                .collect(Collectors.toMap(a -> a.getClass().getSimpleName(), Function.identity()));
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 执行整条链：组装链（缓存）→ 逐阶段执行 → 返回出站请求规格已就绪的上下文。
     * 阶段失败（验签/解码/映射/编码）直接抛 BizException，不落运行表（M0-01 D7）。
     */
    public AdapterContext execute(long interfaceId, UnifiedModel inboundPayload, String traceId, byte[] rawBody) {
        return execute(interfaceId, inboundPayload, traceId, rawBody, Map.of());
    }

    /**
     * 同 {@link #execute}；initialAttrs 为链执行前的附加上下文
     * （入站回调经此传入请求头 headers，供 INBOUND_AUTH 回调验签读取）。
     * M4：整链与六阶段各建 Observation span（apicenter.chain / apicenter.chain.stage，
     * 经 micrometer-observation 桥接 OTel；业务 traceId 以 span tag business.traceId 关联）。
     */
    public AdapterContext execute(long interfaceId, UnifiedModel inboundPayload, String traceId, byte[] rawBody,
                                  Map<String, Object> initialAttrs) {
        InterfaceRow iface = interfaceRepository.findById(interfaceId)
                .orElseThrow(() -> BizException.ifaceNotFound(interfaceId));
        AppRow app = appRepository.findById(iface.appId())
                .orElseThrow(() -> BizException.appNotFound(iface.appId()));

        return Observation.createNotStarted("apicenter.chain", observationRegistry)
                .lowCardinalityKeyValue("interfaceId", String.valueOf(interfaceId))
                .lowCardinalityKeyValue("appId", app.appId())
                .lowCardinalityKeyValue("ifType", iface.ifType())
                .highCardinalityKeyValue("business.traceId", traceId == null ? "" : traceId)
                .observe(() -> doExecute(iface, app, inboundPayload, traceId, rawBody, initialAttrs));
    }

    private AdapterContext doExecute(InterfaceRow iface, AppRow app, UnifiedModel inboundPayload,
                                     String traceId, byte[] rawBody, Map<String, Object> initialAttrs) {
        Chain chain = chain(iface, traceId);
        AdapterContext ctx = AdapterContext.create(
                ChainPhase.INBOUND_AUTH, inboundPayload,
                AdapterContext.InterfaceMeta.of(iface),
                new AdapterContext.AppMeta(app.appId(), app.baseUrl()),
                new AdapterContext.TraceMeta(traceId),
                null, new com.deepx.apicenter.client.OutboundRequestSpec());
        ctx.attrs().put("rawBody", rawBody);
        initialAttrs.forEach(ctx.attrs()::put);

        for (ChainPhase phase : ChainPhase.values()) {
            ctx.phase(phase);
            ChainStep step = chain.steps.get(phase);
            if (step == null) {
                continue; // 无该阶段槽位（如 Flow A 入站鉴权 Noop 占位）
            }
            ChainStep current = step;
            AdapterContext currentCtx = ctx;
            // M5 留痕通道 2：链 stage span 追加绑定角色 tag adapter.{role} = id:impl:version（低基数可作维度）
            String role = roleOf(phase);
            AdapterInstance bound = role == null ? null : chain.boundByRole.get(role);
            Observation obs = Observation.createNotStarted("apicenter.chain.stage", observationRegistry)
                    .lowCardinalityKeyValue("stage", phase.name());
            if (bound != null) {
                obs.lowCardinalityKeyValue("adapter." + role, bound.adapterId() + ":" + bound.impl() + ":" + bound.version());
            }
            ctx = obs.observe(() -> current.execute(currentCtx));
        }
        return ctx;
    }

    /** 阶段 → 绑定角色名（仅三角色有 span tag；协议 / 映射固定步骤不参与绑定） */
    private String roleOf(ChainPhase phase) {
        return switch (phase) {
            case INBOUND_AUTH -> "CALLBACK_AUTH";
            case MESSAGE -> "MESSAGE";
            case OUTBOUND_AUTH -> "AUTH";
            default -> null;
        };
    }

    // ---------- 链组装与缓存 ----------

    private Chain chain(InterfaceRow iface) {
        return chain(iface, null);
    }

    private Chain chain(InterfaceRow iface, String traceId) {
        CachedChain cached = chainCache.get(iface.id());
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.chain;
        }
        Chain chain = assemble(iface);
        chainCache.put(iface.id(), new CachedChain(chain, System.currentTimeMillis() + 5 * 60_000L));
        // M5 留痕通道 1：链装配日志（含 traceId——首次触达请求；量 = 缓存未命中频次，低）
        log.info("链装配 iface={} code={} traceId={} 绑定解析 → {}",
                iface.id(), iface.code(), traceId == null ? "" : traceId, describe(chain.boundByRole));
        return chain;
    }

    private String describe(Map<String, AdapterInstance> bound) {
        return bound.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().adapterId() + ":" + e.getValue().impl() + ":" + e.getValue().version())
                .collect(Collectors.joining(" "));
    }

    /**
     * 链装配（M5 解析时机上移）：六阶段槽位填充，绑定 / 映射 / 参数一次解析烘焙；
     * 凭证注入保留在执行期（每请求实时读，见 OUTBOUND_AUTH step）。
     */
    private Chain assemble(InterfaceRow iface) {
        Map<ChainPhase, ChainStep> steps = new java.util.EnumMap<>(ChainPhase.class);
        Map<String, AdapterInstance> bound = new LinkedHashMap<>();
        String ifType = iface.ifType();
        List<InterfaceRow.BindingRow> binds = interfaceRepository.findBindings(iface.id());
        AppRow app = appRepository.findById(iface.appId()).orElseThrow();

        // 1. 入站鉴权：Flow A 调用方鉴权属平台统一能力（范围外）→ Noop 占位；
        //    Flow B 回调验签（M3 交付）：CALLBACK_AUTH 角色解析（接口覆盖 → 应用默认 → 平台默认 Noop）
        AdapterInstance callbackAuth = "INBOUND".equals(ifType)
                ? resolveBound("CALLBACK_AUTH", iface, binds, app.callbackAuthAdapterId())
                : null;
        if (callbackAuth != null) {
            bound.put("CALLBACK_AUTH", callbackAuth);
        }
        AdapterInstance callbackAuthFinal = callbackAuth;
        steps.put(ChainPhase.INBOUND_AUTH, ctx -> {
            if (!"INBOUND".equals(ifType)) {
                ctx.attrs().put("inboundAuthPassed", true);
                return ctx;
            }
            return callbackAuthFinal.process(ctx);
        });

        // 2. 协议解码（protocol_in 自动推导，不参与绑定；M0-01 D5 平台默认参数）
        //    入站参数声明（XML 全文本解码类型提示，D-M3-1）随装配烘焙
        Adapter decodeIn = protocolAdapter(iface.protocolIn(), "解码");
        Map<String, String> inParamTypes = interfaceRepository.findParams(iface.id()).stream()
                .filter(p -> "IN".equals(p.side()))
                .collect(Collectors.toMap(InterfaceRow.ParamRow::name, InterfaceRow.ParamRow::type, (a, b) -> a, LinkedHashMap::new));
        steps.put(ChainPhase.DECODE, ctx -> {
            ctx.attrs().put("adapterParams", objectMapper.createObjectNode());
            ctx.attrs().put("paramTypes", inParamTypes);
            return decodeIn.process(ctx);
        });

        // 3. 报文适配（MESSAGE 角色：接口覆盖 → 应用默认 → 平台默认 Noop 直通）
        AdapterInstance message = resolveBound("MESSAGE", iface, binds, app.defaultMessageAdapterId());
        bound.put("MESSAGE", message);
        steps.put(ChainPhase.MESSAGE, ctx -> message.process(ctx));

        // 4. 字段映射（固定步骤，非适配器，M0-01 D3）：映射规则随装配烘焙（不再每请求查库）
        List<InterfaceRow.MappingRow> rules = interfaceRepository.findMappings(iface.id());
        steps.put(ChainPhase.MAPPING, ctx -> mappingEngine.apply(ctx, rules));

        // 5. 协议编码（protocol_out 自动推导）
        Adapter encodeOut = protocolAdapter(iface.protocolOut(), "编码");
        steps.put(ChainPhase.ENCODE, ctx -> {
            ctx.attrs().put("adapterParams", objectMapper.createObjectNode());
            return encodeOut.process(ctx);
        });

        // 6. 出站鉴权（AUTH 角色：接口覆盖 → 应用默认 → 平台默认 Noop；凭证注入 attrs）
        AdapterInstance auth = "OUTBOUND".equals(ifType)
                ? resolveBound("AUTH", iface, binds, app.authAdapterId())
                : null;
        if (auth != null) {
            bound.put("AUTH", auth);
        }
        AdapterInstance authFinal = auth;
        steps.put(ChainPhase.OUTBOUND_AUTH, ctx -> {
            if (!"OUTBOUND".equals(ifType)) {
                // D-M3-2：入站送达向回调地址签名默认无（设计 §5.3「可选，默认无」）——
                // 不解析应用默认供应商签名、不注入出站凭证（评审缺陷 1 修复：防供应商密钥外泄给回调地址）
                return instanceOf("NoopAuthAdapter", objectMapper.createObjectNode()).process(ctx);
            }
            injectCredential(ctx, iface.appId());
            return authFinal.process(ctx);
        });

        return new Chain(steps, bound);
    }

    /** 协议适配器自动推导（M0-01 §5.1）：JSON / XML 双实现（XML 为 M3 交付） */
    private Adapter protocolAdapter(String protocol, String action) {
        if ("JSON".equals(protocol)) {
            return bean("JsonProtocolAdapter");
        }
        if ("XML".equals(protocol)) {
            return bean("XmlProtocolAdapter");
        }
        throw BizException.fieldInvalid("协议 " + protocol + " " + action + " 未实现");
    }

    /**
     * 响应方向解码（D-M3-4）：按 protocol_out 推导协议适配器，与请求方向同一条 DECODE 路径。
     * 响应方向不做入站参数类型转换（类型由 RESP 过滤按 field_def.type 转换，见 D-M3-3）。
     */
    public UnifiedModel decodeResponse(long interfaceId, byte[] body) {
        InterfaceRow iface = interfaceRepository.findById(interfaceId)
                .orElseThrow(() -> BizException.ifaceNotFound(interfaceId));
        AppRow app = appRepository.findById(iface.appId())
                .orElseThrow(() -> BizException.appNotFound(iface.appId()));
        Adapter adapter = protocolAdapter(iface.protocolOut(), "响应解码");
        AdapterContext ctx = AdapterContext.create(
                ChainPhase.DECODE, UnifiedModel.emptyObject(),
                AdapterContext.InterfaceMeta.of(iface),
                new AdapterContext.AppMeta(app.appId(), app.baseUrl()),
                new AdapterContext.TraceMeta(null),
                null, new com.deepx.apicenter.client.OutboundRequestSpec());
        ctx.attrs().put("rawBody", body);
        return adapter.process(ctx).payload();
    }

    // ---------- 绑定解析（M0-01 §5.1 + M5 D-M5-2 矩阵 #1-#4，装配时执行） ----------

    /**
     * 绑定解析（M5 矩阵定稿）：
     * #1 adapter_id 空 → 应用默认（应用默认空 → 平台默认 Noop）
     * #2 adapter_id 非空、version 空 → 该行（须 enabled=1）
     * #3 adapter_id 非空、version 非空 → 同 impl + 指定 version + enabled=1 定位行（灰度路径）
     * #4 目标行停用或缺失 → 逐层回退 + log.warn（接口绑定 → 应用默认 → 平台默认 Noop）
     * 注意：绑定行存在但 adapter_id 为空 = 「显式继承应用默认」，回退到 appDefaultAdapterId
     * （管理面前端恒提交空绑定行，不能直接判空兜底吞掉应用级配置）。
     */
    private AdapterInstance resolveBound(String role, InterfaceRow iface, List<InterfaceRow.BindingRow> binds,
                                         String appDefaultAdapterId) {
        InterfaceRow.BindingRow binding = binds.stream()
                .filter(b -> role.equals(b.role()))
                .findFirst()
                .orElse(null);
        boolean ifaceBound = binding != null && binding.adapterId() != null && !binding.adapterId().isBlank();
        if (ifaceBound) {
            AdapterInstance inst = resolveBindingRow(binding, role);
            if (inst != null) {
                return inst;
            }
            log.warn("适配器绑定回退：role={} iface={} 绑定 adapter={} 不可用（缺失 / 停用 / 指定版本缺失）→ 应用默认",
                    role, iface.code(), binding.adapterId());
        }
        if (appDefaultAdapterId != null && !appDefaultAdapterId.isBlank()) {
            AdapterRow def = adapterRepository.findById(appDefaultAdapterId).orElse(null);
            if (def != null && def.enabled()) {
                return instanceOf(def);
            }
            log.warn("适配器应用默认回退：role={} iface={} 应用默认 adapter={} 不可用 → 平台默认 Noop",
                    role, iface.code(), appDefaultAdapterId);
        }
        return defaultInstance(role);
    }

    /** 绑定行解析（矩阵 #2 / #3）；返回 null = 该绑定不可用 → 上层按 #4 回退 */
    private AdapterInstance resolveBindingRow(InterfaceRow.BindingRow binding, String role) {
        AdapterRow base = adapterRepository.findById(binding.adapterId()).orElse(null);
        if (base == null) {
            return null;
        }
        if (binding.version() == null || binding.version().isBlank()) {
            // 矩阵 #2：version 空 → 绑定行所指实例（enabled 校验，停用即回退）
            return base.enabled() ? instanceOf(base) : null;
        }
        // 矩阵 #3：灰度——version 非空 → 同 impl + 指定版本 + enabled=1 定位行；
        // 绑定行所指实例停用同样视为不可用（停用即回退统一口径）
        if (!base.enabled()) {
            return null;
        }
        AdapterRow target = adapterRepository.findByImplAndVersionEnabled(base.impl(), binding.version()).orElse(null);
        return target == null ? null : instanceOf(target);
    }

    /** M5 test 端点 chainTrace（留痕通道 3）：强制实时解析（不走缓存），输出本次装配角色明细 */
    public List<ChainTraceItem> traceOf(long interfaceId) {
        InterfaceRow iface = interfaceRepository.findById(interfaceId)
                .orElseThrow(() -> BizException.ifaceNotFound(interfaceId));
        AppRow app = appRepository.findById(iface.appId()).orElseThrow();
        List<InterfaceRow.BindingRow> binds = interfaceRepository.findBindings(interfaceId);
        List<ChainTraceItem> items = new java.util.ArrayList<>();
        String ifType = iface.ifType();
        if ("INBOUND".equals(ifType)) {
            items.add(item("CALLBACK_AUTH", resolveBound("CALLBACK_AUTH", iface, binds, app.callbackAuthAdapterId())));
        }
        items.add(item("MESSAGE", resolveBound("MESSAGE", iface, binds, app.defaultMessageAdapterId())));
        if ("OUTBOUND".equals(ifType)) {
            items.add(item("AUTH", resolveBound("AUTH", iface, binds, app.authAdapterId())));
        }
        return items;
    }

    private ChainTraceItem item(String role, AdapterInstance inst) {
        return new ChainTraceItem(role, inst.adapterId(), inst.impl(), inst.version());
    }

    /** 绑定角色实时实例（供 OutboundEngine 响应信封适配取装配一致的 MESSAGE 实例，D-M5-2） */
    public AdapterInstance boundInstance(long interfaceId, String role) {
        InterfaceRow iface = interfaceRepository.findById(interfaceId)
                .orElseThrow(() -> BizException.ifaceNotFound(interfaceId));
        return chain(iface).boundByRole.get(role);
    }

    private AdapterInstance defaultInstance(String role) {
        return switch (role) {
            case "MESSAGE" -> instanceOf("NoopMessageAdapter", objectMapper.createObjectNode());
            case "AUTH", "CALLBACK_AUTH" -> instanceOf("NoopAuthAdapter", objectMapper.createObjectNode());
            default -> throw BizException.fieldInvalid("未知绑定角色：" + role);
        };
    }

    private AdapterInstance instanceOf(AdapterRow def) {
        Adapter bean = bean(def.impl());
        JsonNode params;
        try {
            params = def.params() == null || def.params().isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(def.params());
        } catch (Exception e) {
            throw BizException.fieldInvalid("适配器参数非法：" + def.id());
        }
        return new AdapterInstance(def.id(), def.impl(), def.version(), bean, params);
    }

    private AdapterInstance instanceOf(String impl, JsonNode params) {
        return new AdapterInstance("PLATFORM-DEFAULT", impl, "1.0", bean(impl), params);
    }

    private Adapter bean(String impl) {
        Adapter bean = adapterBeans.get(impl);
        if (bean == null) {
            throw BizException.fieldInvalid("适配器实现未注册：" + impl);
        }
        return bean;
    }

    /** 出站凭证明文注入 ctx.attrs（M0-04 §3.2：出站签名仅用 ACTIVE；每请求实时读，不进链缓存） */
    private void injectCredential(AdapterContext ctx, String appId) {
        credentialRepository.findActive(appId, "OUTBOUND")
                .map(CredentialRow::credential)
                .map(cryptoService::decrypt)
                .ifPresent(plain -> ctx.attrs().put("outboundCredential", plain));
    }

    // ---------- 链缓存事件失效（M5 D-M5-2，M2 评审 #17 闭环） ----------

    /**
     * 链缓存失效（M5 D-M5-2；2026-09-07 代码评审 H1 修复）：以 {@link TransactionalEventListener} 监听，
     * 事件在事务**提交后**才执行失效——消除「提交前 clear → 并发请求从旧配置重新装配入缓存 →
     * 提交后无二次事件、陈旧链残至 TTL」的竞态窗口（原 @EventListener 同步清缓存早于 commit）。
     * fallbackExecution=true：无事务上下文发布的事件立即执行（兼容非事务发布点）。
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onConfigChanged(ConfigChangedEvent evt) {
        switch (evt.scope()) {
            case INTERFACE -> {
                if (evt.interfaceId() != null) {
                    chainCache.remove(evt.interfaceId());
                    log.info("链缓存失效（INTERFACE {}）", evt.interfaceId());
                }
            }
            case ADAPTER, APP -> {
                chainCache.clear();
                log.info("链缓存全清（{} 事件，影响面不定）", evt.scope());
            }
        }
    }

    // ---------- 内部类型 ----------

    /** 链步骤：适配器实例或固定步骤（统一返回上下文，适配器可替换 payload 等可变载体） */
    @FunctionalInterface
    private interface ChainStep {
        AdapterContext execute(AdapterContext ctx);
    }

    /** 装配结果链：steps（含烘焙的实例与规则）+ 绑定角色 → 实例明细（留痕 / span tag / 一致性读取） */
    private record Chain(Map<ChainPhase, ChainStep> steps, Map<String, AdapterInstance> boundByRole) {
    }

    private record CachedChain(Chain chain, long expireAt) {
    }

    /** 装配明细条目（test 端点 chainTrace / 留痕通道 3）：role / adapterId / impl / version */
    public record ChainTraceItem(String role, String adapterId, String impl, String version) {
    }
}
