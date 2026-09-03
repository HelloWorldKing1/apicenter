package com.deepx.apicenter.engine;

import com.deepx.apicenter.adapter.message.EnvelopeMessageAdapter;
import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AppRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.service.AppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 出站执行引擎（Flow A，设计 §6.1 状态机落地 + M0-03 异常映射表）：
 * 路由（平台侧路径 → PUBLISHED 接口）→ 应用启用校验 → 落 INIT → 链执行（映射）→ 调上游
 * → 结果分类：2xx+信封成败 → SUCCESS；4xx 非 429 → dead_letter + DEAD_LETTER；
 *   5xx/429 短重试耗尽 → COMPENSATING（补偿 worker 兜底）；读超时/连接异常 → UNKNOWN（对账）。
 * 链失败（解码/映射/编码/验签）直接错误响应，不落运行表（M0-01 D7）。
 */
@Service
public class OutboundEngine {

    private static final Logger log = LoggerFactory.getLogger(OutboundEngine.class);

    private final InterfaceRepository interfaceRepository;
    private final AppRepository appRepository;
    private final AdapterRepository adapterRepository;
    private final OutboundRequestRepository outboundRequestRepository;
    private final ChainEngine chainEngine;
    private final UpstreamInvoker upstreamInvoker;
    private final AppService appService;
    private final EnvelopeMessageAdapter envelopeMessageAdapter;
    private final ObjectMapper objectMapper;

    public OutboundEngine(InterfaceRepository interfaceRepository,
                          AppRepository appRepository,
                          AdapterRepository adapterRepository,
                          OutboundRequestRepository outboundRequestRepository,
                          ChainEngine chainEngine,
                          UpstreamInvoker upstreamInvoker,
                          AppService appService,
                          EnvelopeMessageAdapter envelopeMessageAdapter,
                          ObjectMapper objectMapper) {
        this.interfaceRepository = interfaceRepository;
        this.appRepository = appRepository;
        this.adapterRepository = adapterRepository;
        this.outboundRequestRepository = outboundRequestRepository;
        this.chainEngine = chainEngine;
        this.upstreamInvoker = upstreamInvoker;
        this.appService = appService;
        this.envelopeMessageAdapter = envelopeMessageAdapter;
        this.objectMapper = objectMapper;
    }

    /**
     * 接入层入口：路由 + 执行。统一信封回调用方（设计 §6.2）。
     * 链失败抛 BizException（由全局异常处理转信封，不落运行表）。
     */
    public ApiResult<?> dispatch(String path, String method, byte[] body, String bizId, String traceId) {
        // 1. 路由：平台侧路径 → PUBLISHED 接口（40401 接口不存在）
        InterfaceRow iface = interfaceRepository.findByPath(path)
                .orElseThrow(() -> new BizException(40401, "接口不存在：" + path));
        if (!"PUBLISHED".equals(iface.status())) {
            throw new BizException(40401, "接口未发布：" + path);
        }
        if (!iface.method().equalsIgnoreCase(method)) {
            throw new BizException(40401, "接口方法不匹配：" + method + "（期望 " + iface.method() + "）");
        }
        // 2. 应用启用校验（停用即拒，M1 钩子 M2 接入）
        if (!appService.isRequestAllowed(iface.appId())) {
            throw BizException.appDisabled(iface.appId());
        }
        String trace = traceId == null ? UUID.randomUUID().toString().replace("-", "") : traceId;
        String biz = bizId == null || bizId.isBlank() ? UUID.randomUUID().toString() : bizId;
        log.info("路由命中接口 code={} appId={} 上游={}", iface.code(), iface.appId(), iface.upstreamPath());
        return execute(iface, body, biz, trace);
    }

    /** 执行出站链路（首送与补偿重放共用入口） */
    public ApiResult<?> execute(InterfaceRow iface, byte[] body, String bizId, String traceId) {
        long recordId = createRecord(iface, body, bizId, traceId);
        try {
            return doInvoke(recordId, iface, body, traceId);
        } catch (Exception e) {
            BizException mapped = classifyInvokeFailure(recordId, e);
            if (e instanceof BizException) {
                throw e; // 链失败不污染状态机（M0-01 D7）；死信/业务失败已在 doInvoke 内落状态
            }
            log.error("出站请求 {} 执行异常", recordId, e);
            throw mapped;
        }
    }

    // ---------- 首送与重放核心 ----------

    private ApiResult<?> doInvoke(long recordId, InterfaceRow iface, byte[] body, String traceId) {
        // 链执行：入站鉴权 → 解码 → 报文适配 → 字段映射 → 编码 → 出站鉴权（链内统一载体 payload）
        AdapterContext ctx = chainEngine.execute(iface.id(), UnifiedModel.emptyObject(), traceId, body);

        // 出站规格补全：URL / 方法 / 超时（M0-03 §1.2）
        ctx.outbound().url(appOf(iface).baseUrl() + iface.upstreamPath());
        ctx.outbound().method(iface.method());
        ctx.outbound().readTimeoutMs(iface.timeoutMs());

        // 状态 MAPPING → 调上游
        outboundRequestRepository.updateState(recordId, "MAPPING", null, null, null, null);
        UpstreamInvoker.MAX_RETRIES.set((long) iface.maxRetries());
        ResponseEntity<byte[]> resp;
        try {
            resp = upstreamInvoker.invoke(ctx.outbound());
        } finally {
            UpstreamInvoker.MAX_RETRIES.remove();
        }
        return classify(recordId, iface, resp);
    }

    /** 结果分类（M0-03 §2 异常映射表 + C2 业务失败定稿） */
    private ApiResult<?> classify(long recordId, InterfaceRow iface, ResponseEntity<byte[]> resp) {
        HttpStatusCode status = resp.getStatusCode();
        byte[] respBody = resp.getBody() == null ? new byte[0] : resp.getBody();
        if (status.is2xxSuccessful()) {
            return handleSuccess(recordId, iface, respBody);
        }
        // 4xx 非 429 → 死信（不重试；5xx/429 已在 Invoker 内重试，到此即耗尽）
        String reason = "上游 " + status.value() + "：" + resp.getStatusCode();
        outboundRequestRepository.updateState(recordId, "DEAD_LETTER", null, null, null, "50201");
        outboundRequestRepository.insertDeadLetter("OUTBOUND", recordId, reason, bytesText(respBody));
        long deadLetterId = deadLetterId(recordId);
        throw new BizException(50201, "上游拒绝（4xx）：" + reason + "，死信编号 " + deadLetterId);
    }

    /** 2xx：信封适配判业务成败（M0-03 定稿 C2：业务失败也记 SUCCESS、业务码透传） */
    private ApiResult<?> handleSuccess(long recordId, InterfaceRow iface, byte[] respBody) {
        UnifiedModel respModel = parseResponse(respBody);
        String outPayload = modelText(respModel);
        outboundRequestRepository.updateState(recordId, "SUCCESS", null, outPayload, null, null);
        log.info("出站请求 {} 成功（响应 {} 字节）", recordId, respBody.length);

        JsonNode envelopeParams = envelopeParamsOf(iface);
        if (envelopeParams == null) {
            // 直通报文适配器（Noop）：业务成败 = HTTP 状态（已 2xx），整个响应体即业务数据
            return ApiResult.ok(parseLenient(bytesText(respBody)));
        }
        EnvelopeMessageAdapter.EnvelopeResult envelope =
                envelopeMessageAdapter.adaptResponse(respModel, envelopeParams);
        JsonNode dataNode = envelope.bizData() == null ? null : parseLenient(nodeText(envelope.bizData()));
        if (!envelope.success()) {
            // 业务失败：状态机 SUCCESS（传输层已获明确结果），业务码透传（C2）
            return ApiResult.error(parseCode(envelope.code(), 50201),
                    envelope.msg() == null ? "上游业务失败" : envelope.msg());
        }
        return ApiResult.ok(dataNode);
    }

    /**
     * 补偿重放（CompensationWorker 调用）：同一 outbound_request 记录上按 in_payload 重走链
     * （配置/凭证取最新）；重放安全依赖上游对 biz_id 幂等（ADR 5）。
     * 异常归类与首送共用同一分类器：超时/连接异常 → UNKNOWN 对账（不盲目重试），
     * 429/5xx → COMPENSATING 续期（更新 next_retry_at 继续调度）。
     */
    public void replay(OutboundRequestRow row) {
        InterfaceRow iface = interfaceRepository.findById(row.interfaceId())
                .orElseThrow(() -> BizException.ifaceNotFound(row.interfaceId()));
        log.info("补偿重放 outbound_request {}（attempt {}/{}）", row.id(), row.attemptCount() + 1, row.maxAttempts());
        outboundRequestRepository.incrementAttempt(row.id());
        try {
            ApiResult<?> result = doInvoke(row.id(), iface,
                    row.inPayload() == null ? new byte[0]
                            : row.inPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    row.traceId());
            log.info("补偿重放 outbound_request {} 结果 code={}", row.id(), result.code());
        } catch (Exception e) {
            BizException mapped = classifyInvokeFailure(row.id(), e);
            if (e instanceof BizException) {
                // 链失败（D7）与死信/业务失败（doInvoke 内已落状态）不重复分类，仅记录
                log.warn("补偿重放 outbound_request {} 失败：{}", row.id(), mapped.getMessage());
            } else {
                log.error("补偿重放 outbound_request {} 异常（已按 {} 归类）", row.id(), mapped.getCode(), e);
            }
        }
    }

    /**
     * 异常 → 状态机统一分类器（M0-03 §2 映射表，首送与补偿重放共用，消除两路分叉）：
     * ResourceAccessException（读超时/连接异常，@Retryable 耗尽后透传）→ UNKNOWN 对账；
     * TooManyRequests / HttpServerErrorException → COMPENSATING（next_retry_at 续期）；
     * 其余 → 50000（状态不动，由调用方记录）。
     */
    private BizException classifyInvokeFailure(long recordId, Exception e) {
        if (e instanceof org.springframework.web.client.ResourceAccessException) {
            outboundRequestRepository.updateState(recordId, "UNKNOWN", null, null, null, "50401");
            log.info("出站请求 {} 结果不确定（读超时/连接异常）→ UNKNOWN 待对账", recordId);
            return new BizException(50401, "上游超时，结果待对账（UNKNOWN）");
        }
        if (e instanceof org.springframework.web.client.HttpClientErrorException.TooManyRequests) {
            LocalDateTime next = LocalDateTime.now().plusSeconds(3);
            outboundRequestRepository.updateState(recordId, "COMPENSATING", null, null, next, "42903");
            log.info("出站请求 {} 重试耗尽（429）→ COMPENSATING，补偿 worker 兜底", recordId);
            return new BizException(50201, "上游暂时不可用，已进入补偿队列");
        }
        if (e instanceof org.springframework.web.client.HttpServerErrorException) {
            LocalDateTime next = LocalDateTime.now().plusSeconds(3);
            outboundRequestRepository.updateState(recordId, "COMPENSATING", null, null, next, "50201");
            log.info("出站请求 {} 重试耗尽（5xx）→ COMPENSATING，补偿 worker 兜底", recordId);
            return new BizException(50201, "上游暂时不可用，已进入补偿队列");
        }
        return new BizException(50000, "平台内部错误");
    }

    // ---------- 私有 ----------

    private long createRecord(InterfaceRow iface, byte[] body, String bizId, String traceId) {
        return outboundRequestRepository.insert(new OutboundRequestRow(
                0, iface.id(), iface.appId(), bizId,
                body == null ? null : new String(body, java.nio.charset.StandardCharsets.UTF_8),
                null, null, "INIT", 1, // 首送即第 1 次尝试
                iface.maxRetries() + 1, null, null, traceId, null, null));
    }

    private AppRow appOf(InterfaceRow iface) {
        return appRepository.findById(iface.appId()).orElseThrow();
    }

    /** 响应协议解码：按出站协议（M2 仅 JSON；XML M3） */
    private UnifiedModel parseResponse(byte[] body) {
        UnifiedModel model = UnifiedModel.emptyObject();
        try {
            JsonNode node = objectMapper.readTree(body.length == 0 ? new byte[]{'{', '}'} : body);
            model.root(toUnified(node));
        } catch (Exception e) {
            log.warn("响应报文解析失败（按空对象处理）：{}", e.getMessage());
        }
        return model;
    }

    private UnifiedModel.UNode toUnified(JsonNode node) {
        if (node == null || node.isNull()) {
            return UnifiedModel.ScalarNode.nullNode();
        }
        if (node.isObject()) {
            java.util.LinkedHashMap<String, UnifiedModel.UNode> fields = new java.util.LinkedHashMap<>();
            node.properties().forEach(e -> fields.put(e.getKey(), toUnified(e.getValue())));
            return new UnifiedModel.ObjectNode(fields, java.util.Map.of());
        }
        if (node.isArray()) {
            java.util.List<UnifiedModel.UNode> items = new java.util.ArrayList<>();
            node.forEach(n -> items.add(toUnified(n)));
            return new UnifiedModel.ArrayNode(items);
        }
        if (node.isIntegralNumber()) {
            // 超出 long 范围的大整数（BigInteger）→ DECIMAL，不静默截断（M0-01 §1 类型标注）
            if (node.canConvertToLong()) {
                return UnifiedModel.ScalarNode.num(node.longValue());
            }
            return UnifiedModel.ScalarNode.decimal(node.decimalValue());
        }
        if (node.isFloatingPointNumber() || node.isBigDecimal()) {
            return UnifiedModel.ScalarNode.decimal(node.decimalValue());
        }
        if (node.isBoolean()) {
            return UnifiedModel.ScalarNode.bool(node.booleanValue());
        }
        return UnifiedModel.ScalarNode.str(node.asText());
    }

    /**
     * MESSAGE 绑定解析（接口覆盖 → 应用默认）：
     * 命中 EnvelopeMessageAdapter → 返回其 params 用于响应信封适配；
     * 未命中（Noop 直通 / 无绑定）→ 返回 null，业务成败 = HTTP 状态。
     */
    private JsonNode envelopeParamsOf(InterfaceRow iface) {
        String adapterId = interfaceRepository.findBindings(iface.id()).stream()
                .filter(b -> "MESSAGE".equals(b.role()) && b.adapterId() != null)
                .map(InterfaceRow.BindingRow::adapterId)
                .findFirst()
                .orElseGet(() -> appOf(iface).defaultMessageAdapterId());
        if (adapterId == null || adapterId.isBlank()) {
            return null;
        }
        return adapterRepository.findById(adapterId)
                .filter(def -> "EnvelopeMessageAdapter".equals(def.impl()))
                .map(def -> parseLenient(def.params()))
                .orElse(null);
    }

    private long deadLetterId(long recordId) {
        return recordId; // M2 简化：ref_id 即出站记录 id（dead_letter.ref_id 多态引用）
    }

    private String bytesText(byte[] body) {
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String modelText(UnifiedModel model) {
        return nodeText(model.root());
    }

    private String nodeText(UnifiedModel.UNode node) {
        try {
            // 复用 ObjectMapper 序列化（JsonProtocolAdapter 的逆操作在此内联）
            return objectMapper.writeValueAsString(toJsonNode(node));
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode toJsonNode(UnifiedModel.UNode node) {
        return switch (node) {
            case UnifiedModel.ObjectNode obj -> {
                tools.jackson.databind.node.ObjectNode out = objectMapper.createObjectNode();
                obj.fields().forEach((k, v) -> out.set(k, toJsonNode(v)));
                yield out;
            }
            case UnifiedModel.ArrayNode arr -> {
                tools.jackson.databind.node.ArrayNode out = objectMapper.createArrayNode();
                arr.items().forEach(v -> out.add(toJsonNode(v)));
                yield out;
            }
            case UnifiedModel.ScalarNode s -> switch (s.type()) {
                case STRING -> objectMapper.getNodeFactory().textNode((String) s.value());
                case INT -> objectMapper.getNodeFactory().numberNode((Long) s.value());
                case DECIMAL -> objectMapper.getNodeFactory().numberNode((java.math.BigDecimal) s.value());
                case BOOLEAN -> objectMapper.getNodeFactory().booleanNode((Boolean) s.value());
                case NULL -> objectMapper.getNodeFactory().nullNode();
            };
        };
    }

    private JsonNode parseLenient(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private int parseCode(String code, int def) {
        try {
            return Integer.parseInt(code);
        } catch (Exception e) {
            return def;
        }
    }
}
