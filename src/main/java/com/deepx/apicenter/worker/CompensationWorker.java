package com.deepx.apicenter.worker;

import com.deepx.apicenter.service.OrderSyncService.RequestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 补偿 worker（设计文档 §6.4，能力 3.2「持久化补偿」）。
 *
 * <p>周期（retry-worker-fixed-delay-ms=3000ms）扫描两类待补偿记录并重放：
 * <ul>
 *   <li>出站 {@code integration_request.status=COMPENSATING}（短重试耗尽后转入）</li>
 *   <li>入站 {@code callback_delivery.delivery_status=PENDING}（送达 ERP 失败）</li>
 * </ul>
 *
 * <p>Demo 为内存/单机扫描；生产要点：加分布式锁防多实例并发、按 next_retry_at 到期重放、
 * 指数退避（仍失败保持 COMPENSATING）、补偿超过上限转死信。
 */
@Component
public class CompensationWorker {

    private static final Logger log = LoggerFactory.getLogger(CompensationWorker.class);

    private final JdbcTemplate jdbcTemplate;

    public CompensationWorker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 每 3000ms 扫描一次（对齐 application.yaml retry-worker-fixed-delay-ms）。
     */
    @Scheduled(fixedDelayString = "${app.integration.retry-worker-fixed-delay-ms}")
    public void compensate() {
        compensateOutbound();
        compensateInbound();
    }

    /**
     * 出站补偿：重放 COMPENSATING 请求。
     * 示例仅打日志；生产按 request_payload 反序列化 → PartnerInvoker.invoke → 成功置 SUCCESS，
     * 仍失败更新 next_retry_at 保持 COMPENSATING（指数等待），超限转死信。
     */
    private void compensateOutbound() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM integration_request WHERE status = ?", Long.class, RequestStatus.COMPENSATING.name());
        for (Long id : ids) {
            log.info("[comp-worker] 重放出站请求 requestId={}", id);
            // TODO 生产实现：反序列化 payload → partnerInvoker.invoke → updateStatus
        }
    }

    /**
     * 入站补偿：重发 PENDING 送达记录到 ERP。
     * 示例仅打日志；生产按 payload 反序列化 → erpCallbackClient.deliver → 置 ERP_ACKED。
     */
    private void compensateInbound() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM callback_delivery WHERE delivery_status = ?", Long.class, "PENDING");
        for (Long id : ids) {
            log.info("[comp-worker] 重发入站送达 deliveryId={}", id);
            // TODO 生产实现：反序列化 payload → erpCallbackClient.deliver → updateDelivery
        }
    }
}
