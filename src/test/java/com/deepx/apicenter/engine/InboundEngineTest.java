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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InboundEngine 单测（M3 计划 §4）：链失败不落运行表（D7）/ ack 与送达解耦（送达失败仍回 ack）/
 * 快照语义 / callback_event_id 提取 / 应用停用与接口未发布拒绝（40102/40401）/ 送达 4xx 直接死信。
 */
class InboundEngineTest {

    private final InterfaceRepository interfaceRepository = mock(InterfaceRepository.class);
    private final AppService appService = mock(AppService.class);
    private final ChainEngine chainEngine = mock(ChainEngine.class);
    private final UpstreamInvoker upstreamInvoker = mock(UpstreamInvoker.class);
    private final InboundDeliveryRepository inboundDeliveryRepository = mock(InboundDeliveryRepository.class);
    private final OutboundRequestRepository outboundRequestRepository = mock(OutboundRequestRepository.class);
    private final AckRenderer ackRenderer = mock(AckRenderer.class);
    private final CallbackUrlValidator callbackUrlValidator = mock(CallbackUrlValidator.class);

    private final InboundEngine engine = new InboundEngine(interfaceRepository, appService, chainEngine,
            upstreamInvoker, inboundDeliveryRepository, outboundRequestRepository, ackRenderer, callbackUrlValidator);

    private final InterfaceRow iface = new InterfaceRow(7L, "IF-CB-01", "回调演示", "INBOUND", "POST",
            "/callback/demo/order", "JSON", "JSON", "M3DEMO", 1L,
            null, "http://localhost:18080/delivery-ok", "PUBLISHED", 1,
            3000, 4, null, LocalDateTime.now(), LocalDateTime.now(), null, null);

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @BeforeEach
    void setUp() {
        when(interfaceRepository.findByPath("/callback/demo/order")).thenReturn(Optional.of(iface));
        when(interfaceRepository.findById(7L)).thenReturn(Optional.of(iface)); // redeliver 走 findById
        when(appService.isRequestAllowed("M3DEMO")).thenReturn(true);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));
        when(request.getHeader(anyString())).thenReturn(null);
        when(callbackUrlValidator.isAllowed(anyString())).thenReturn(true);
        when(ackRenderer.render(any(), any()))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{}".getBytes(StandardCharsets.UTF_8)));

        AdapterContext ctx = AdapterContext.create(ChainPhase.ENCODE, UnifiedModel.emptyObject(),
                AdapterContext.InterfaceMeta.of(iface), new AdapterContext.AppMeta("M3DEMO", "http://x"),
                new AdapterContext.TraceMeta("t1"), null, new OutboundRequestSpec());
        ctx.outbound().body("{\"order_id\":\"ORD-1\"}".getBytes(StandardCharsets.UTF_8));
        ctx.payload().set("event_id", UnifiedModel.ScalarNode.str("evt-1"));
        when(chainEngine.execute(anyLong(), any(), anyString(), any(), any())).thenReturn(ctx);
        when(inboundDeliveryRepository.insert(any())).thenReturn(100L);
    }

    // ---------- 路由校验 ----------

    @Test
    void 接口未发布40401() {
        InterfaceRow draft = new InterfaceRow(7L, "IF-CB-01", "回调演示", "INBOUND", "POST",
                "/callback/demo/order", "JSON", "JSON", "M3DEMO", 1L,
                null, "http://localhost:18080/delivery-ok", "DRAFT", 1,
                3000, 4, null, LocalDateTime.now(), LocalDateTime.now(), null, null);
        when(interfaceRepository.findByPath("/callback/demo/order")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> engine.handle(request, "/callback/demo/order", "POST", new byte[0], "t"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40401));
    }

    @Test
    void 应用停用40102() {
        when(appService.isRequestAllowed("M3DEMO")).thenReturn(false);
        assertThatThrownBy(() -> engine.handle(request, "/callback/demo/order", "POST", new byte[0], "t"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40102));
    }

    @Test
    void 链失败不落运行表D7() {
        when(chainEngine.execute(anyLong(), any(), anyString(), any(), any()))
                .thenThrow(new BizException(40002, "报文格式非法"));
        assertThatThrownBy(() -> engine.handle(request, "/callback/demo/order", "POST", new byte[0], "t"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40002));
        verify(inboundDeliveryRepository, never()).insert(any()); // 不落运行表
    }

    // ---------- 送达与 ack ----------

    @Test
    void 送达成功转ACKED且回ack() {
        when(upstreamInvoker.invoke(any())).thenReturn(
                ResponseEntity.status(HttpStatusCode.valueOf(200)).body(new byte[0]));

        ResponseEntity<byte[]> resp = engine.handle(request, "/callback/demo/order", "POST", new byte[0], "t");

        verify(inboundDeliveryRepository).updateState(100L, "ACKED", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200); // ack 恒 200
    }

    @Test
    void 送达失败仍回ackPENDING续期() {
        when(upstreamInvoker.invoke(any()))
                .thenThrow(new HttpServerErrorException(HttpStatusCode.valueOf(500), "5xx"));

        ResponseEntity<byte[]> resp = engine.handle(request, "/callback/demo/order", "POST", new byte[0], "t");

        // ack 与送达解耦：异常不向上抛，响应仍 200
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(inboundDeliveryRepository).updateState(eq(100L), eq("PENDING"), any(LocalDateTime.class));
    }

    @Test
    void 送达4xx直接死信() {
        when(upstreamInvoker.invoke(any())).thenReturn(
                ResponseEntity.status(HttpStatusCode.valueOf(404)).body(new byte[0]));

        engine.handle(request, "/callback/demo/order", "POST", new byte[0], "t");

        verify(inboundDeliveryRepository).updateState(100L, "DEAD_LETTER", null);
        verify(outboundRequestRepository).insertDeadLetter("INBOUND", 100L,
                "送达 4xx（HTTP 404）", "{\"order_id\":\"ORD-1\"}");
    }

    @Test
    void 落库快照语义() {
        when(upstreamInvoker.invoke(any())).thenReturn(
                ResponseEntity.status(HttpStatusCode.valueOf(200)).body(new byte[0]));

        engine.handle(request, "/callback/demo/order", "POST", new byte[0], "t");

        ArgumentCaptor<InboundDeliveryRow> captor = ArgumentCaptor.forClass(InboundDeliveryRow.class);
        verify(inboundDeliveryRepository).insert(captor.capture());
        InboundDeliveryRow row = captor.getValue();
        assertThat(row.callbackUrlSnapshot()).isEqualTo("http://localhost:18080/delivery-ok"); // 接口当前回调地址快照
        assertThat(row.callbackEventId()).isEqualTo("evt-1"); // 报文顶层 event_id 提取
        assertThat(row.deliveryStatus()).isEqualTo("PENDING"); // 首期落库即 PENDING（D-M3-2 崩溃安全）
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.maxAttempts()).isEqualTo(5); // max_retries + 1
        assertThat(row.ackToPartner()).isEqualTo("ACKED"); // 收到即回
    }

    // ---------- 重送 ----------

    @Test
    void 重送按快照地址不随接口改址漂移() {
        InboundDeliveryRow row = new InboundDeliveryRow(100L, 7L, "M3DEMO", "evt-1",
                "{\"order_id\":\"ORD-1\"}", "http://old-snapshot/delivery", "PENDING",
                2, 5, LocalDateTime.now().minusSeconds(1), "ACKED", "t",
                LocalDateTime.now(), LocalDateTime.now());
        when(upstreamInvoker.invoke(any())).thenReturn(
                ResponseEntity.status(HttpStatusCode.valueOf(200)).body(new byte[0]));

        engine.redeliver(row);

        verify(inboundDeliveryRepository).incrementAttempt(100L);
        ArgumentCaptor<OutboundRequestSpec> captor = ArgumentCaptor.forClass(OutboundRequestSpec.class);
        verify(upstreamInvoker).invoke(captor.capture());
        assertThat(captor.getValue().url()).isEqualTo("http://old-snapshot/delivery"); // 按快照，不按接口当前地址
        assertThat(captor.getValue().method()).isEqualTo("POST"); // 首期固定 POST
        verify(inboundDeliveryRepository).updateState(100L, "ACKED", null);
    }

    @Test
    void 重送超时保持PENDING() {
        InboundDeliveryRow row = new InboundDeliveryRow(100L, 7L, "M3DEMO", "evt-1",
                "{\"order_id\":\"ORD-1\"}", "http://old-snapshot/delivery", "PENDING",
                2, 5, LocalDateTime.now().minusSeconds(1), "ACKED", "t",
                LocalDateTime.now(), LocalDateTime.now());
        when(upstreamInvoker.invoke(any())).thenThrow(
                new org.springframework.web.client.ResourceAccessException("timeout"));

        engine.redeliver(row);

        verify(inboundDeliveryRepository).updateState(eq(100L), eq("PENDING"), any(LocalDateTime.class));
    }
}
