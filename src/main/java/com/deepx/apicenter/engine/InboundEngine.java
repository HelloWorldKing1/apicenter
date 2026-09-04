package com.deepx.apicenter.engine;

import com.deepx.apicenter.client.OutboundRequestSpec;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.InboundDeliveryRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.InboundDeliveryRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.service.AppService;
import com.deepx.apicenter.service.CallbackUrlValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;

/**
 * 入站执行引擎（Flow B，M3 交付，D-M3-2）：供应商回调 → 验签（INBOUND_AUTH）→ 链 → 落 PENDING → 同步送达 → ack。
 * 关键语义：
 * - 落库即 PENDING、next_retry_at=now+5s（防 worker 与同步送达并发重放；崩溃后由 worker 自然兜底），
 *   送达成功转 ACKED，RECEIVED 态保留不启用（schema 无变更）；
 * - ack 与送达解耦：无论送达成败都回 ack（HTTP 200，AckRenderer 渲染），供应商不重发；
 * - 送达 4xx（非 429）→ 直接死信；5xx/429 短重试耗尽 / 超时 → 保持 PENDING（入站无 UNKNOWN 对账态）；
 * - 重放按 payload 快照 + callback_url_snapshot（不重新走链、不随接口改址漂移），Content-Type 按接口当前 protocol_out 推导；
 * - 验签 / 链失败抛 BizException → 全局异常处理转统一信封（401/400 等），不落运行表（M0-01 D7）。
 */
@Service
public class InboundEngine {

    private static final Logger log = LoggerFactory.getLogger(InboundEngine.class);

    /** 落库初始延迟（D-M3-2：覆盖短重试最坏窗口，防 worker 抢跑） */
    static final int INITIAL_DELIVERY_DELAY_SECONDS = 5;
    /** 送达失败后 PENDING 续期间隔（与补偿 worker 扫描周期一致） */
    static final int RETRY_INTERVAL_SECONDS = 3;

    private final InterfaceRepository interfaceRepository;
    private final AppService appService;
    private final ChainEngine chainEngine;
    private final UpstreamInvoker upstreamInvoker;
    private final InboundDeliveryRepository inboundDeliveryRepository;
    private final OutboundRequestRepository outboundRequestRepository;
    private final AckRenderer ackRenderer;
    private final CallbackUrlValidator callbackUrlValidator;

    public InboundEngine(InterfaceRepository interfaceRepository,
                         AppService appService,
                         ChainEngine chainEngine,
                         UpstreamInvoker upstreamInvoker,
                         InboundDeliveryRepository inboundDeliveryRepository,
                         OutboundRequestRepository outboundRequestRepository,
                         AckRenderer ackRenderer,
                         CallbackUrlValidator callbackUrlValidator) {
        this.interfaceRepository = interfaceRepository;
        this.appService = appService;
        this.chainEngine = chainEngine;
        this.upstreamInvoker = upstreamInvoker;
        this.inboundDeliveryRepository = inboundDeliveryRepository;
        this.outboundRequestRepository = outboundRequestRepository;
        this.ackRenderer = ackRenderer;
        this.callbackUrlValidator = callbackUrlValidator;
    }

    /** 接入层入口：路由校验 → 链执行 → 落库 → 同步送达 → 回 ack（裸报文出口） */
    public ResponseEntity<byte[]> handle(HttpServletRequest request, String path, String method,
                                         byte[] body, String traceId) {
        // 1. 路由（与出站对称）：PUBLISHED + 方法匹配 + 应用启用（40102）
        InterfaceRow iface = interfaceRepository.findByPath(path)
                .orElseThrow(() -> new BizException(40401, "接口不存在：" + path));
        if (!"INBOUND".equals(iface.ifType())) {
            throw new BizException(40401, "接口不存在：" + path); // 网关已按 ifType 分流，此处防御
        }
        if (!"PUBLISHED".equals(iface.status())) {
            throw new BizException(40401, "接口未发布：" + path);
        }
        if (!iface.method().equalsIgnoreCase(method)) {
            throw new BizException(40401, "接口方法不匹配：" + method + "（期望 " + iface.method() + "）");
        }
        if (!appService.isRequestAllowed(iface.appId())) {
            throw BizException.appDisabled(iface.appId());
        }
        String trace = traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : traceId;
        log.info("入站回调命中接口 code={} appId={}", iface.code(), iface.appId());

        // 2. 链执行：验签（INBOUND_AUTH，失败 40100/40101 抛异常不落运行表）→ 解码 → 报文适配 → 映射 → 编码
        Map<String, String> headers = readHeaders(request);
        AdapterContext ctx = chainEngine.execute(iface.id(), UnifiedModel.emptyObject(), trace,
                body == null ? new byte[0] : body, Map.<String, Object>of("headers", headers));

        // 3. 落库 PENDING（next_retry_at=now+5s 防 worker 抢跑；崩溃后由 worker 兜底）
        byte[] payloadBytes = ctx.outbound().body();
        long deliveryId = inboundDeliveryRepository.insert(new InboundDeliveryRow(
                0, iface.id(), iface.appId(),
                extractEventId(ctx, headers),
                payloadBytes == null ? null : new String(payloadBytes, StandardCharsets.UTF_8),
                iface.callbackUrl(),
                "PENDING", 1, iface.maxRetries() + 1,
                LocalDateTime.now().plusSeconds(INITIAL_DELIVERY_DELAY_SECONDS),
                "ACKED", trace, null, null));

        // 4. 同步送达（复用 UpstreamInvoker 短重试 5xx/429；首期固定 POST；SSRF 运行时兜底）
        ctx.outbound().url(iface.callbackUrl());
        ctx.outbound().method("POST");
        deliver(deliveryId, iface, ctx.outbound());

        // 5. 无论成败回 ack（与送达解耦，D-M3-3 渲染）
        return ackRenderer.render(iface, ctx.payload());
    }

    /** 补偿重送（CompensationWorker 调用）：按 payload 快照 + callback_url_snapshot 重放，不重新走链 */
    public void redeliver(InboundDeliveryRow row) {
        // 条件认领（评审遗漏 6 修复）：仅 PENDING 才 attempt+1——并发双扫（调度 + 手动 scan）时
        // 已被他线程处理至 ACKED/DEAD 的记录不再重复送达
        if (!inboundDeliveryRepository.claimForRedeliver(row.id())) {
            log.warn("inbound_delivery {} 已被其他扫描线程处理（非 PENDING），放弃重放", row.id());
            return;
        }
        log.info("送达重送 inbound_delivery {}（attempt {}/{}）", row.id(), row.attemptCount() + 1, row.maxAttempts());
        InterfaceRow iface = interfaceRepository.findById(row.interfaceId())
                .orElseThrow(() -> BizException.ifaceNotFound(row.interfaceId()));
        OutboundRequestSpec spec = new OutboundRequestSpec();
        spec.url(row.callbackUrlSnapshot());
        spec.method("POST");
        spec.body(row.payload() == null ? new byte[0] : row.payload().getBytes(StandardCharsets.UTF_8));
        // 重放 Content-Type 按接口当前 protocol_out 推导（D-M3-2：不锁传输元数据，不匹配则送达失败 → 死信）
        if ("XML".equals(iface.protocolOut())) {
            spec.header("Content-Type", "application/xml");
            spec.header("Accept", "application/xml");
        } else {
            spec.header("Content-Type", "application/json");
            spec.header("Accept", "application/json");
        }
        deliver(row.id(), iface, spec);
    }

    /** 送达公共路径：SSRF 兜底 → 短重试调用 → 2xx ACKED / 4xx 死信 / 异常保持 PENDING 续期。
     *  运行表写入经 safe* 包裹（评审缺陷 2 修复）：送达侧任何异常不冒泡，保证 ack 恒 200（与送达解耦）。 */
    private void deliver(long deliveryId, InterfaceRow iface, OutboundRequestSpec spec) {
        spec.readTimeoutMs(iface.timeoutMs());
        if (!callbackUrlValidator.isAllowed(spec.url())) {
            safeState(deliveryId, "DEAD_LETTER", null);
            safeDeadLetter(deliveryId, "回调地址安全校验失败（SSRF）", payloadText(spec.body()));
            log.warn("inbound_delivery {} 回调地址安全校验失败 → 死信", deliveryId);
            return;
        }
        UpstreamInvoker.MAX_RETRIES.set((long) iface.maxRetries());
        try {
            ResponseEntity<byte[]> resp = upstreamInvoker.invoke(spec);
            if (resp.getStatusCode().is2xxSuccessful()) {
                safeState(deliveryId, "ACKED", null);
                log.info("inbound_delivery {} 送达成功 → ACKED", deliveryId);
            } else {
                // 4xx（非 429）→ 直接死信（对齐出站「4xx 不重试」语义；死因写 dead_letter.reason）
                safeState(deliveryId, "DEAD_LETTER", null);
                safeDeadLetter(deliveryId,
                        "送达 4xx（HTTP " + resp.getStatusCode().value() + "）", payloadText(spec.body()));
                log.warn("inbound_delivery {} 送达 4xx → 死信", deliveryId);
            }
        } catch (Exception e) {
            // 5xx/429 短重试耗尽 / 超时 / 连接异常 → 保持 PENDING 并续期（入站无 UNKNOWN 对账态，重送安全按 ADR5）
            safeState(deliveryId, "PENDING", LocalDateTime.now().plusSeconds(RETRY_INTERVAL_SECONDS));
            log.warn("inbound_delivery {} 送达失败 → PENDING 待重送（{}）", deliveryId, e.getClass().getSimpleName());
        } finally {
            UpstreamInvoker.MAX_RETRIES.remove();
        }
    }

    /** 运行表状态写入兜底：DB 异常仅记日志不冒泡（ack 与送达解耦不受影响，worker 下次扫描兜底） */
    private void safeState(long deliveryId, String status, LocalDateTime nextRetryAt) {
        try {
            inboundDeliveryRepository.updateState(deliveryId, status, nextRetryAt);
        } catch (Exception e) {
            log.error("inbound_delivery {} 状态落库失败（{}，ack 仍回，由 worker 兜底）", deliveryId, status, e);
        }
    }

    /** 死信写入兜底：同 safeState */
    private void safeDeadLetter(long deliveryId, String reason, String payload) {
        try {
            insertDeadLetterOnce(deliveryId, reason, payload);
        } catch (Exception e) {
            log.error("inbound_delivery {} 死信落库失败（ack 仍回）", deliveryId, e);
        }
    }

    /** 死信防重插入（并发双扫：首送与重放、调度与手动扫描可能并发命中同一记录） */
    private void insertDeadLetterOnce(long deliveryId, String reason, String payload) {
        if (outboundRequestRepository.countDeadLetter("INBOUND", deliveryId) == 0) {
            outboundRequestRepository.insertDeadLetter("INBOUND", deliveryId, reason, payload);
        }
    }

    /** callback_event_id 提取（D-M3-2）：回调报文顶层字段 event_id 优先，其次 Header X-Event-Id，取不到为 NULL */
    private String extractEventId(AdapterContext ctx, Map<String, String> headers) {
        UnifiedModel.UNode node = ctx.payload().get("event_id").orElse(null);
        if (node instanceof UnifiedModel.ScalarNode s && s.type() != UnifiedModel.ScalarType.NULL) {
            return String.valueOf(s.value());
        }
        String fromHeader = headers.get("X-Event-Id");
        return fromHeader == null || fromHeader.isBlank() ? null : fromHeader;
    }

    private Map<String, String> readHeaders(HttpServletRequest request) {
        // 大小写不敏感（评审 N1 修复）：HTTP 头名规范不区分大小写，Servlet 不保证返回客户端原始大小写，
        // 验签适配器按 X-Timestamp / 签名头精确取值——用 TreeMap(CASE_INSENSITIVE_ORDER) 兜住小写头名
        Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private String payloadText(byte[] body) {
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }
}
