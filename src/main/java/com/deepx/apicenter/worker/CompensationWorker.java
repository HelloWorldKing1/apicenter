package com.deepx.apicenter.worker;

import com.deepx.apicenter.engine.OutboundEngine;
import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 补偿 worker（设计 §6.3）：定时扫描 COMPENSATING 记录按 (status, next_retry_at) 重试；
 * 超 max_attempts → 死信 + DEAD_LETTER。重放安全依赖上游对 biz_id 幂等（ADR 5）。
 * 入站 PENDING 重送为 M3（inbound_delivery）。
 */
@Component
public class CompensationWorker {

    private static final Logger log = LoggerFactory.getLogger(CompensationWorker.class);

    private final OutboundRequestRepository outboundRequestRepository;
    private final OutboundEngine outboundEngine;

    public CompensationWorker(OutboundRequestRepository outboundRequestRepository,
                              OutboundEngine outboundEngine) {
        this.outboundRequestRepository = outboundRequestRepository;
        this.outboundEngine = outboundEngine;
    }

    @Scheduled(fixedDelayString = "${app.api-center.retry-worker-fixed-delay-ms:3000}")
    public void scan() {
        List<OutboundRequestRow> due = outboundRequestRepository.findDueCompensating(LocalDateTime.now());
        for (OutboundRequestRow row : due) {
            try {
                if (row.attemptCount() >= row.maxAttempts()) {
                    // 补偿耗尽 → 死信 + 告警（M4 接入告警通道）
                    outboundRequestRepository.updateState(row.id(), "DEAD_LETTER", null, null, null, "50201");
                    outboundRequestRepository.insertDeadLetter("OUTBOUND", row.id(),
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
}
