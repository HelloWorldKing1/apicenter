package com.deepx.apicenter.engine;

import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.mapping.MappingEngine;
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

    private static final Logger log = LoggerFactory.getLogger(ChainEngine.class);

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
        return execute(interfaceId, inboundPayload, traceId, rawBody, Map.of());
    }

    /**
     * 同 {@link #execute}；initialAttrs 为链执行前的附加上下文
     * （入站回调经此传入请求头 headers，供 INBOUND_AUTH 回调验签读取）。
     */
    public AdapterContext execute(long interfaceId, UnifiedModel inboundPayload, String traceId, byte[] rawBody,
                                  Map<String, Object> initialAttrs) {
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
        initialAttrs.forEach(ctx.attrs()::put);

        for (ChainPhase phase : ChainPhase.values()) {
            ctx.phase(phase);
            ChainStep step = chain.steps.get(phase);
            if (step == null) {
                continue; // 无该阶段槽位（如 Flow A 入站鉴权 Noop 占位）
            }
            ctx = step.execute(ctx);
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

        // 1. 入站鉴权：Flow A 调用方鉴权属平台统一能力（范围外）→ Noop 占位；
        //    Flow B 回调验签（M3 交付）：CALLBACK_AUTH 角色解析（接口覆盖 → 应用默认 → 平台默认 Noop）
        steps.put(ChainPhase.INBOUND_AUTH, ctx -> {
            if (!"INBOUND".equals(iface.ifType())) {
                ctx.attrs().put("inboundAuthPassed", true);
                return ctx;
            }
            AdapterInstance instance = resolveBound("CALLBACK_AUTH", iface,
                    ifaceBinding(iface.id(), "CALLBACK_AUTH"),
                    appOf(iface).callbackAuthAdapterId());
            return instance.process(ctx);
        });

        // 2. 协议解码（protocol_in 自动推导，不参与绑定；M0-01 D5 平台默认参数）
        steps.put(ChainPhase.DECODE, ctx -> {
            Adapter adapter = protocolAdapter(iface.protocolIn(), "解码");
            ctx.attrs().put("adapterParams", objectMapper.createObjectNode());
            // XML 全文本解码的类型提示（D-M3-1）：入站参数声明 → TypeRegistry 转换（仅顶层字段）
            ctx.attrs().put("paramTypes", interfaceRepository.findParams(iface.id()).stream()
                    .filter(p -> "IN".equals(p.side()))
                    .collect(Collectors.toMap(InterfaceRow.ParamRow::name, InterfaceRow.ParamRow::type, (a, b) -> a)));
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
            if ("INBOUND".equals(iface.ifType())) {
                // D-M3-2：入站送达向回调地址签名默认无（设计 §5.3「可选，默认无」）——
                // 不解析应用默认供应商签名、不注入出站凭证（评审缺陷 1 修复：防供应商密钥外泄给回调地址）
                return instanceOf("NoopAuthAdapter", objectMapper.createObjectNode()).process(ctx);
            }
            AdapterInstance instance = resolveBound("AUTH", iface,
                    ifaceBinding(iface.id(), "AUTH"),
                    appOf(iface).authAdapterId());
            injectCredential(ctx, appOf(iface).appId());
            log.info("出站鉴权解析 impl={} adapterId={} 凭证已注入={}",
                    instance.impl(), instance.adapterId(),
                    ctx.attrs().get("outboundCredential") != null);
            return instance.process(ctx);
        });

        return new Chain(steps);
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

    /**
     * 绑定解析（M0-01 §5.1，定稿 D4）：接口绑定（adapter_id 非空）→ 应用默认 → 平台默认。
     * 注意：绑定行存在但 adapter_id 为空 = 「显式继承应用默认」，必须回退到 appDefaultAdapterId，
     * 不能直接判空兜底（管理面前端恒提交空绑定行，会吞掉应用级配置）。
     */
    private AdapterInstance resolveBound(String role, InterfaceRow iface,
                                        InterfaceRow.BindingRow binding, String appDefaultAdapterId) {
        String adapterId = (binding != null && binding.adapterId() != null && !binding.adapterId().isBlank())
                ? binding.adapterId()
                : appDefaultAdapterId;
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

    /** 链步骤：适配器实例或固定步骤（统一返回上下文，适配器可替换 payload 等可变载体） */
    @FunctionalInterface
    private interface ChainStep {
        AdapterContext execute(AdapterContext ctx);
    }

    private record Chain(Map<ChainPhase, ChainStep> steps) {
    }

    private record CachedChain(Chain chain, long expireAt) {
    }
}
