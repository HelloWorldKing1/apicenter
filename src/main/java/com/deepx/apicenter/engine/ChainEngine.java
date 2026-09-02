package com.deepx.apicenter.engine;

import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.mapping.MappingEngine;
import com.deepx.apicenter.model.AdapterRow;
import com.deepx.apicenter.model.AppRow;
import com.deepx.apicenter.model.CredentialRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.CredentialRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.service.CryptoService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 适配器链引擎（M0-01 契约落地）：
 * - 固定六阶段链（§4）：入站鉴权 → 协议解码 → 报文适配 → 字段映射（固定步骤）→ 协议编码 → 出站鉴权
 * - 绑定解析（§5）：MESSAGE/AUTH 角色按「接口覆盖 → 应用默认 → 平台默认」解析；
 *   协议按 protocol_in/out 自动推导（M2 仅 JsonProtocolAdapter，XML 为 M3 交付）；
 *   回调验签 CALLBACK_AUTH 仅 INBOUND 生效（M2 无入站链路，解析但暂不执行）
 * - 链缓存（§7）：key=interface_id，TTL 5 分钟兜底（事件失效 M5 灰度时补齐）
 * - 链失败不污染状态机（§6）：解码/映射/编码/验签失败直接 BizException，不落运行表
 */
@Component
public class ChainEngine {

    private final InterfaceRepository interfaceRepository;
    private final AppRepository appRepository;
    private final AdapterRepository adapterRepository;
    private final CredentialRepository credentialRepository;
    private final CryptoService cryptoService;
    private final MappingEngine mappingEngine;
    private final Map<String, Adapter> adapterBeans;
    private final ObjectMapper objectMapper;

    private final Map<Long, CachedChain> chainCache = new ConcurrentHashMap<>();

    public ChainEngine(InterfaceRepository interfaceRepository,
                       AppRepository appRepository,
                       AdapterRepository adapterRepository,
                       CredentialRepository credentialRepository,
                       CryptoService cryptoService,
                       MappingEngine mappingEngine,
                       List<Adapter> adapters,
                       ObjectMapper objectMapper) {
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
    }

    /**
     * 执行整条链：组装链（缓存）→ 逐阶段执行 → 返回出站请求规格已就绪的上下文。
     * 阶段失败（验签/解码/映射/编码）直接抛 BizException，不落运行表（M0-01 D7）。
     */
    public AdapterContext execute(long interfaceId, UnifiedModel inboundPayload, String traceId, byte[] rawBody) {
        InterfaceRow iface = interfaceRepository.findById(interfaceId)
                .orElseThrow(() -> BizException.ifaceNotFound(interfaceId));
        AppRow app = appRepository.findById(iface.appId())
                .orElseThrow(() -> BizException.appNotFound(iface.appId()));

        Chain chain = chain(iface);
        AdapterContext ctx = AdapterContext.create(
                ChainPhase.INBOUND_AUTH, inboundPayload,
                AdapterContext.InterfaceMeta.of(iface),
                new AdapterContext.AppMeta(app.appId(), app.baseUrl()),
                new AdapterContext.TraceMeta(traceId),
                null, new com.deepx.apicenter.client.OutboundRequestSpec());
        ctx.attrs().put("rawBody", rawBody);

        for (ChainPhase phase : ChainPhase.values()) {
            ctx.phase(phase);
            ChainStep step = chain.steps.get(phase);
            if (step == null) {
                continue; // 无该阶段槽位（如 Flow A 入站鉴权 Noop 占位）
            }
            step.execute(ctx);
        }
        return ctx;
    }

    // ---------- 链组装与缓存 ----------

    private Chain chain(InterfaceRow iface) {
        CachedChain cached = chainCache.get(iface.id());
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.chain;
        }
        Chain chain = assemble(iface);
        chainCache.put(iface.id(), new CachedChain(chain, System.currentTimeMillis() + 5 * 60_000L));
        return chain;
    }

    /** 链组装：六阶段槽位填充（M0-01 §4/§5） */
    private Chain assemble(InterfaceRow iface) {
        Map<ChainPhase, ChainStep> steps = new java.util.EnumMap<>(ChainPhase.class);

        // 1. 入站鉴权：Flow A 调用方鉴权属平台统一能力（范围外）→ Noop 占位；Flow B 回调验签（M3 接入）
        steps.put(ChainPhase.INBOUND_AUTH, ctx -> {
            ctx.attrs().put("inboundAuthPassed", true);
            return ctx;
        });

        // 2. 协议解码（protocol_in 自动推导，不参与绑定；M0-01 D5 平台默认参数）
        steps.put(ChainPhase.DECODE, ctx -> {
            Adapter adapter = protocolAdapter(iface.protocolIn(), "解码");
            ctx.attrs().put("adapterParams", objectMapper.createObjectNode());
            return adapter.process(ctx);
        });

        // 3. 报文适配（MESSAGE 角色：接口覆盖 → 应用默认 → 平台默认 Noop 直通）
        steps.put(ChainPhase.MESSAGE, ctx -> {
            AdapterInstance instance = resolveBound("MESSAGE", iface,
                    ifaceBinding(iface.id(), "MESSAGE"),
                    appOf(iface).defaultMessageAdapterId());
            return instance.process(ctx);
        });

        // 4. 字段映射（固定步骤，非适配器，M0-01 D3）：映射引擎读接口级规则执行
        steps.put(ChainPhase.MAPPING, mappingEngine::apply);

        // 5. 协议编码（protocol_out 自动推导）
        steps.put(ChainPhase.ENCODE, ctx -> {
            Adapter adapter = protocolAdapter(iface.protocolOut(), "编码");
            ctx.attrs().put("adapterParams", objectMapper.createObjectNode());
            return adapter.process(ctx);
        });

        // 6. 出站鉴权（AUTH 角色：接口覆盖 → 应用默认 → 平台默认 Noop；凭证注入 attrs）
        steps.put(ChainPhase.OUTBOUND_AUTH, ctx -> {
            AdapterInstance instance = resolveBound("AUTH", iface,
                    ifaceBinding(iface.id(), "AUTH"),
                    appOf(iface).authAdapterId());
            injectCredential(ctx, appOf(iface).appId());
            return instance.process(ctx);
        });

        return new Chain(steps);
    }

    /** 协议适配器自动推导（M0-01 §5.1）：M2 仅 JSON；XML 为 M3 交付 */
    private Adapter protocolAdapter(String protocol, String action) {
        if ("JSON".equals(protocol)) {
            return bean("JsonProtocolAdapter");
        }
        throw BizException.fieldInvalid("协议 " + protocol + " " + action + " 未实现（XML 为 M3 交付）");
    }

    /** 绑定解析：接口绑定 → 应用默认 → 平台默认（M0-01 §5.1，定稿 D4） */
    private AdapterInstance resolveBound(String role, InterfaceRow iface,
                                        InterfaceRow.BindingRow binding, String appDefaultAdapterId) {
        String adapterId = binding != null ? binding.adapterId() : appDefaultAdapterId;
        if (adapterId == null || adapterId.isBlank()) {
            return defaultInstance(role);
        }
        AdapterRow def = adapterRepository.findById(adapterId)
                .orElseThrow(() -> BizException.fieldInvalid("绑定的适配器不存在：" + adapterId));
        return instanceOf(def);
    }

    private AdapterInstance defaultInstance(String role) {
        return switch (role) {
            case "MESSAGE" -> instanceOf("NoopMessageAdapter", objectMapper.createObjectNode());
            case "AUTH" -> instanceOf("NoopAuthAdapter", objectMapper.createObjectNode());
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

    /** 出站凭证明文注入 ctx.attrs（M0-04 §3.2：出站签名仅用 ACTIVE） */
    private void injectCredential(AdapterContext ctx, String appId) {
        credentialRepository.findActive(appId, "OUTBOUND")
                .map(CredentialRow::credential)
                .map(cryptoService::decrypt)
                .ifPresent(plain -> ctx.attrs().put("outboundCredential", plain));
    }

    private InterfaceRow.BindingRow ifaceBinding(long interfaceId, String role) {
        return interfaceRepository.findBindings(interfaceId).stream()
                .filter(b -> role.equals(b.role()))
                .findFirst()
                .orElse(null);
    }

    private AppRow appOf(InterfaceRow iface) {
        return appRepository.findById(iface.appId()).orElseThrow();
    }

    // ---------- 内部类型 ----------

    /** 链步骤：适配器实例或固定步骤 */
    @FunctionalInterface
    private interface ChainStep {
        void execute(AdapterContext ctx);
    }

    private record Chain(Map<ChainPhase, ChainStep> steps) {
    }

    private record CachedChain(Chain chain, long expireAt) {
    }
}
