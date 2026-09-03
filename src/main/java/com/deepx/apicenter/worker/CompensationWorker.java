package com.deepx.apicenter.worker;

import com.deepx.apicenter.engine.InboundEngine;
import com.deepx.apicenter.engine.OutboundEngine;
import com.deepx.apicenter.model.InboundDeliveryRow;
import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.repository.InboundDeliveryRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 补偿 worker（设计 §6.3）：定时扫描按 (status, next_retry_at) 重试。
 * - 出站：COMPENSATING 记录 → 重放（重放安全依赖上游对 biz_id 幂等，ADR 5）；
 * - 入站（M3 交付）：PENDING 记录 → 按 payload 快照 + callback_url_snapshot 重送（不重新走链、不随接口改址漂移）；
 * 两者超 max_attempts → 死信 + DEAD_LETTER（告警 M4 接入）。
 */
@Component
public class CompensationWorker {

    private static final Logger log = LoggerFactory.getLogger(CompensationWorker.class);

    private final OutboundRequestRepository outboundRequestRepository;
    private final OutboundEngine outboundEngine;
    private final InboundDeliveryRepository inboundDeliveryRepository;
    private final InboundEngine inboundEngine;

    public CompensationWorker(OutboundRequestRepository outboundRequestRepository,
                              OutboundEngine outboundEngine,
                              InboundDeliveryRepository inboundDeliveryRepository,
                              InboundEngine inboundEngine) {
        this.outboundRequestRepository = outboundRequestRepository;
        this.outboundEngine = outboundEngine;
        this.inboundDeliveryRepository = inboundDeliveryRepository;
        this.inboundEngine = inboundEngine;
    }

    @Scheduled(fixedDelayString = "${app.api-center.retry-worker-fixed-delay-ms:3000}")
    public void scan() {
        scanOutbound();
        scanInbound();
    }

    /** 出站补偿：COMPENSATING → 重放；耗尽 → 死信 + 告警（M4 接入告警通道） */
    private void scanOutbound() {
        List<OutboundRequestRow> due = outboundRequestRepository.findDueCompensating(LocalDateTime.now());
        for (OutboundRequestRow row : due) {
            try {
                if (row.attemptCount() >= row.maxAttempts()) {
                    outboundRequestRepository.updateState(row.id(), "DEAD_LETTER", null, null, null, "50201");
                    insertDeadLetterOnce("OUTBOUND", row.id(),
                            "补偿重试耗尽（attempt " + row.attemptCount() + "/" + row.maxAttempts() + "）",
                            row.inPayload());
                    log.warn("outbound_request {} 补偿耗尽 → 死信", row.id());
                    continue;
                }
                outboundEngine.replay(row);
            } catch (Exception e) {
                // 单行异常隔离：一行失败不影响本批次其余记录（高危 #2 修复）
                log.error("补偿扫描处理 outbound_request {} 失败，跳过该行", row.id(), e);
            }
        }
    }

    /** 死信防重插入（并发双扫：调度线程与手动调用可能同时命中同一记录） */
    private void insertDeadLetterOnce(String bizType, long refId, String reason, String payload) {
        if (outboundRequestRepository.countDeadLetter(bizType, refId) == 0) {
            outboundRequestRepository.insertDeadLetter(bizType, refId, reason, payload);
        }
    }

    /** 入站重送（M3）：PENDING → 重送；耗尽 → 死信（biz_type=INBOUND） */
    private void scanInbound() {
        List<InboundDeliveryRow> due = inboundDeliveryRepository.findDuePending(LocalDateTime.now());
        for (InboundDeliveryRow row : due) {
            try {
                if (row.attemptCount() >= row.maxAttempts()) {
                    inboundDeliveryRepository.updateState(row.id(), "DEAD_LETTER", null);
                    insertDeadLetterOnce("INBOUND", row.id(),
                            "送达重试耗尽（attempt " + row.attemptCount() + "/" + row.maxAttempts() + "）",
                            row.payload());
                    log.warn("inbound_delivery {} 重送耗尽 → 死信", row.id());
                    continue;
                }
                inboundEngine.redeliver(row);
            } catch (Exception e) {
                // 单行异常隔离：一行失败不影响本批次其余记录
                log.error("补偿扫描处理 inbound_delivery {} 失败，跳过该行", row.id(), e);
            }
        }
    }
}
