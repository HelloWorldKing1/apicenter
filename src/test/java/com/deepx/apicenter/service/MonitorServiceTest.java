package com.deepx.apicenter.service;

import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.repository.AlertEventRepository;
import com.deepx.apicenter.repository.CallLogRepository;
import com.deepx.apicenter.repository.DeadLetterRepository;
import com.deepx.apicenter.repository.InboundDeliveryRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.repository.ReconcileAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
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
 * MonitorService 单测（M4 计划 §4）：人工对账三分支 / 非 UNKNOWN 拒绝 / TTL 降级与审计 /
 * 死信重放状态重置（OUTBOUND / INBOUND / 已处理拒绝 / ref 缺失拒绝）。
 */
class MonitorServiceTest {

    private final OutboundRequestRepository outboundRequestRepository = mock(OutboundRequestRepository.class);
    private final InboundDeliveryRepository inboundDeliveryRepository = mock(InboundDeliveryRepository.class);
    private final ReconcileAuditRepository reconcileAuditRepository = mock(ReconcileAuditRepository.class);
    private final DeadLetterRepository deadLetterRepository = mock(DeadLetterRepository.class);
    private final AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
    private final CallLogRepository callLogRepository = mock(CallLogRepository.class);

    private final MonitorService service = new MonitorService(outboundRequestRepository,
            inboundDeliveryRepository, reconcileAuditRepository, deadLetterRepository,
            alertEventRepository, callLogRepository);

    private OutboundRequestRow unknownRow(long id) {
        return new OutboundRequestRow(id, 7L, "APP", "BIZ-1", "{}", null, null,
                "UNKNOWN", 1, 5, null, "50401", "trace-1",
                LocalDateTime.now(), LocalDateTime.now());
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "unknownTtlMinutes", 10L);
        when(outboundRequestRepository.findById(1L)).thenReturn(Optional.of(unknownRow(1)));
        when(outboundRequestRepository.findById(2L)).thenReturn(Optional.of(new OutboundRequestRow(
                2, 7L, "APP", "BIZ-2", "{}", null, null,
                "SUCCESS", 1, 5, null, null, "trace-2", LocalDateTime.now(), LocalDateTime.now())));
    }

    @Test
    void 人工对账_置位SUCCESS_审计MANUAL() {
        service.reconcile(1, "SUCCESS", "admin", "上游确认已到达");
        verify(outboundRequestRepository).clearErrorCode(1);
        verify(outboundRequestRepository).updateState(eq(1L), eq("SUCCESS"), any(), any(), any(), any());
        verify(reconcileAuditRepository).insert(1, "UNKNOWN", "SUCCESS", "MANUAL", "admin", "上游确认已到达");
    }

    @Test
    void 人工对账_置位COMPENSATING_立即入队() {
        ArgumentCaptor<LocalDateTime> nextCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        service.reconcile(1, "COMPENSATING", "admin", "上游确认未到达");
        verify(outboundRequestRepository).updateState(eq(1L), eq("COMPENSATING"), any(), any(),
                nextCaptor.capture(), any());
        assertThat(nextCaptor.getValue()).isNotNull();
        verify(reconcileAuditRepository).insert(1, "UNKNOWN", "COMPENSATING", "MANUAL", "admin", "上游确认未到达");
    }

    @Test
    void 人工对账_非UNKNOWN拒绝() {
        assertThatThrownBy(() -> service.reconcile(2, "SUCCESS", "admin", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅 UNKNOWN");
        verify(reconcileAuditRepository, never()).insert(anyLong(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void 人工对账_非法目标拒绝() {
        assertThatThrownBy(() -> service.reconcile(1, "DEAD_LETTER", "admin", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("SUCCESS / COMPENSATING");
    }

    @Test
    void 人工对账_operator必填() {
        assertThatThrownBy(() -> service.reconcile(1, "SUCCESS", " ", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("操作人");
    }

    @Test
    void TTL降级_转COMPENSATING并审计TTL来源() {
        when(outboundRequestRepository.findUnknownExpired(any(LocalDateTime.class)))
                .thenReturn(java.util.List.of(unknownRow(1)));
        int n = service.downgradeExpiredUnknown();
        assertThat(n).isEqualTo(1);
        verify(outboundRequestRepository).updateState(eq(1L), eq("COMPENSATING"), any(), any(), any(), any());
        verify(reconcileAuditRepository).insert(eq(1L), eq("UNKNOWN"), eq("COMPENSATING"), eq("TTL"),
                eq("TTL-WORKER"), anyString());
    }

    @Test
    void TTL降级_无到期记录零处理() {
        when(outboundRequestRepository.findUnknownExpired(any(LocalDateTime.class)))
                .thenReturn(java.util.List.of());
        assertThat(service.downgradeExpiredUnknown()).isZero();
        verify(reconcileAuditRepository, never()).insert(anyLong(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    private DeadLetterRepository.DeadLetterView dead(long id, String bizType, Long refId, String status) {
        return new DeadLetterRepository.DeadLetterView(id, bizType, refId, "测试死因", "{}", status, null, null);
    }

    @Test
    void 死信重放_OUTBOUND_状态重置加HANDLED() {
        when(deadLetterRepository.findById(11L)).thenReturn(Optional.of(dead(11, "OUTBOUND", 1L, "PENDING")));
        when(outboundRequestRepository.findById(1L)).thenReturn(Optional.of(unknownRow(1)));
        service.replayDeadLetter(11);
        verify(outboundRequestRepository).resetForReplay(1); // COMPENSATING + attempt=0
        verify(deadLetterRepository).markHandled(11);
    }

    @Test
    void 死信重放_INBOUND_置回PENDING() {
        when(deadLetterRepository.findById(12L)).thenReturn(Optional.of(dead(12, "INBOUND", 9L, "PENDING")));
        when(inboundDeliveryRepository.findById(9L)).thenReturn(Optional.of(new com.deepx.apicenter.model.InboundDeliveryRow(
                9, 7L, "APP", "evt", "{}", "http://cb", "DEAD_LETTER", 5, 5, null, "ACKED", "t",
                LocalDateTime.now(), LocalDateTime.now())));
        service.replayDeadLetter(12);
        verify(inboundDeliveryRepository).resetForReplay(9); // PENDING + attempt=0
        verify(deadLetterRepository).markHandled(12);
    }

    @Test
    void 死信重放_已处理拒绝() {
        when(deadLetterRepository.findById(13L)).thenReturn(Optional.of(dead(13, "OUTBOUND", 1L, "HANDLED")));
        assertThatThrownBy(() -> service.replayDeadLetter(13))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已处理");
        verify(outboundRequestRepository, never()).resetForReplay(anyLong());
    }

    @Test
    void 死信重放_关联记录缺失拒绝() {
        when(deadLetterRepository.findById(14L)).thenReturn(Optional.of(dead(14, "OUTBOUND", 99L, "PENDING")));
        when(outboundRequestRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.replayDeadLetter(14))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        verify(deadLetterRepository, never()).markHandled(anyLong());
    }
}
