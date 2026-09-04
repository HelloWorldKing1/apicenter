package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.model.ReconcileAuditRow;
import com.deepx.apicenter.repository.AlertEventRepository;
import com.deepx.apicenter.repository.CallLogRepository;
import com.deepx.apicenter.repository.DeadLetterRepository;
import com.deepx.apicenter.repository.InboundDeliveryRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.repository.ReconcileAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 监控服务（M4 交付，设计 §4「接口监控」模块落地）：
 * - UNKNOWN 对账（D-M4-2）：人工置位（补 M2 缺口）+ TTL 超时自动降级 + reconcile_audit 审计留痕；
 * - 死信管理（D-M4-3）：查看 / 重放（= 重新入队：状态重置后由 worker 自然重放，零新执行路径）；
 * - 统计与查询（D-M4-5）：监控页五类数据源（overview / call-logs / 运行记录 / 死信 / 告警）。
 */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    /** TTL 降级后的补偿间隔（与 classifyInvokeFailure 固定 +3s 口径一致） */
    private static final int TTL_RETRY_INTERVAL_SECONDS = 3;

    private final OutboundRequestRepository outboundRequestRepository;
    private final InboundDeliveryRepository inboundDeliveryRepository;
    private final ReconcileAuditRepository reconcileAuditRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final AlertEventRepository alertEventRepository;
    private final CallLogRepository callLogRepository;

    /** UNKNOWN 超时自动降级时长（分钟，M0-03 §3.1；已有配置项，M4 消费） */
    @Value("${app.api-center.unknown-ttl-minutes:10}")
    private long unknownTtlMinutes;

    public MonitorService(OutboundRequestRepository outboundRequestRepository,
                          InboundDeliveryRepository inboundDeliveryRepository,
                          ReconcileAuditRepository reconcileAuditRepository,
                          DeadLetterRepository deadLetterRepository,
                          AlertEventRepository alertEventRepository,
                          CallLogRepository callLogRepository) {
        this.outboundRequestRepository = outboundRequestRepository;
        this.inboundDeliveryRepository = inboundDeliveryRepository;
        this.reconcileAuditRepository = reconcileAuditRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.alertEventRepository = alertEventRepository;
        this.callLogRepository = callLogRepository;
    }

    // ---------- 对账（D-M4-2） ----------

    /**
     * 人工对账置位（M0-03 C3 M2 范围补缺）：仅 UNKNOWN 可操作；
     * SUCCESS → 收敛（error_code 清空）；COMPENSATING → next_retry_at=now 立即入队由 worker 重放。
     * 审计落 reconcile_audit（source=MANUAL）——管理面无用户体系（M1 现状），operator 由前端弹窗填写。
     */
    public OutboundRequestRow reconcile(long outboundRequestId, String target, String operator, String reason) {
        if (!"SUCCESS".equals(target) && !"COMPENSATING".equals(target)) {
            throw BizException.fieldInvalid("对账目标仅允许 SUCCESS / COMPENSATING，当前：" + target);
        }
        if (operator == null || operator.isBlank()) {
            throw BizException.fieldInvalid("操作人（operator）必填");
        }
        OutboundRequestRow row = outboundRequestRepository.findById(outboundRequestId)
                .orElseThrow(() -> BizException.fieldInvalid("出站记录不存在：" + outboundRequestId));
        if (!"UNKNOWN".equals(row.status())) {
            throw BizException.fieldInvalid("仅 UNKNOWN 状态可对账，当前：" + row.status());
        }
        if ("SUCCESS".equals(target)) {
            // 已到达：收敛成功；error_code 清空（updateState COALESCE 语义——null 不覆盖，需显式清空列）
            outboundRequestRepository.clearErrorCode(outboundRequestId);
            outboundRequestRepository.updateState(outboundRequestId, "SUCCESS", null, null, null, null);
        } else {
            // 未到达：转补偿立即入队（重放携带同一 biz_id，去重依赖上游幂等，ADR 5）
            outboundRequestRepository.updateState(outboundRequestId, "COMPENSATING", null, null,
                    LocalDateTime.now(), null);
        }
        reconcileAuditRepository.insert(outboundRequestId, row.status(), target, "MANUAL", operator, reason);
        log.info("人工对账 outbound_request {}：UNKNOWN → {}（operator={}）", outboundRequestId, target, operator);
        return outboundRequestRepository.findById(outboundRequestId).orElse(row);
    }

    /**
     * TTL 超时自动降级（M0-03 §3.1 分支二，CompensationWorker 周期调用）：
     * UNKNOWN 持续超 unknown_ttl → 自动转 COMPENSATING（error_code 保持 50401 保留超时成因）+ 审计（source=TTL）。
     */
    public int downgradeExpiredUnknown() {
        LocalDateTime expireBefore = LocalDateTime.now().minusMinutes(unknownTtlMinutes);
        List<OutboundRequestRow> expired = outboundRequestRepository.findUnknownExpired(expireBefore);
        for (OutboundRequestRow row : expired) {
            outboundRequestRepository.updateState(row.id(), "COMPENSATING", null, null,
                    LocalDateTime.now().plusSeconds(TTL_RETRY_INTERVAL_SECONDS), null);
            reconcileAuditRepository.insert(row.id(), "UNKNOWN", "COMPENSATING", "TTL",
                    "TTL-WORKER", "UNKNOWN 超过 " + unknownTtlMinutes + " 分钟自动降级（重放依赖上游幂等，ADR 5）");
            log.info("UNKNOWN 超时降级 outbound_request {}（updated_at 超 {} 分钟）→ COMPENSATING", row.id(), unknownTtlMinutes);
        }
        return expired.size();
    }

    /** 对账审计查询（监控页 / 手动验收查证） */
    public List<ReconcileAuditRow> audits(long outboundRequestId) {
        return reconcileAuditRepository.findByOutboundRequest(outboundRequestId);
    }

    // ---------- 死信管理（D-M4-3） ----------

    /**
     * 死信重放 = 重新入队（技术架构 §4.7）：状态重置复用既有 replay / redeliver 路径，零新执行逻辑。
     * OUTBOUND → outbound_request 置回 COMPENSATING + attempt=0；INBOUND → inbound_delivery 置回 PENDING
     * + attempt=0（payload / callback_url_snapshot 不变）；dead_letter → HANDLED + handled_at。
     * 仅 PENDING 死信可重放（防重）；出站重放自然经熔断闸门。
     */
    public void replayDeadLetter(long deadLetterId) {
        DeadLetterRepository.DeadLetterView dead = deadLetterRepository.findById(deadLetterId)
                .orElseThrow(() -> BizException.fieldInvalid("死信不存在：" + deadLetterId));
        if (!"PENDING".equals(dead.status())) {
            throw BizException.fieldInvalid("死信已处理（HANDLED），不可重复重放：" + deadLetterId);
        }
        if (dead.refId() == null) {
            throw BizException.fieldInvalid("死信缺少关联运行记录（ref_id 为空），无法重放");
        }
        switch (dead.bizType() == null ? "" : dead.bizType()) {
            case "OUTBOUND" -> {
                OutboundRequestRow row = outboundRequestRepository.findById(dead.refId())
                        .orElseThrow(() -> BizException.fieldInvalid(
                                "死信关联出站记录不存在：" + dead.refId()));
                outboundRequestRepository.resetForReplay(row.id());
                log.info("死信 {} 重放：outbound_request {} 置回 COMPENSATING（attempt 清零）", deadLetterId, row.id());
            }
            case "INBOUND" -> {
                if (inboundDeliveryRepository.findById(dead.refId()).isEmpty()) {
                    throw BizException.fieldInvalid("死信关联送达记录不存在：" + dead.refId());
                }
                inboundDeliveryRepository.resetForReplay(dead.refId());
                log.info("死信 {} 重放：inbound_delivery {} 置回 PENDING（attempt 清零）", deadLetterId, dead.refId());
            }
            default -> throw BizException.fieldInvalid("死信类型未知：" + dead.bizType());
        }
        deadLetterRepository.markHandled(deadLetterId);
    }

    // ---------- 监控页查询（D-M4-5） ----------

    /** 统计卡（惰性缓存由 Controller / 前端轮询控制，此处直查） */
    public ApiResult<MonitorOverview> overview() {
        long todayIn = callLogRepository.countTodayIn();
        // 今日成功率 = 今日终态（SUCCESS + DEAD_LETTER）中 SUCCESS 占比；无终态时为 100（无失败样本）
        long todaySuccess = countTodayByStatus("SUCCESS");
        long todayDead = countTodayByStatus("DEAD_LETTER");
        long denominator = todaySuccess + todayDead;
        double successRate = denominator == 0 ? 100.0 : (double) todaySuccess * 100 / denominator;
        return ApiResult.ok(new MonitorOverview(
                todayIn,
                Math.round(successRate * 10) / 10.0,
                todaySuccess, todayDead,
                outboundRequestRepository.countByStatus("COMPENSATING"),
                inboundDeliveryRepository.countByStatus("PENDING"),
                deadLetterRepository.countPending(),
                outboundRequestRepository.countByStatus("UNKNOWN")));
    }

    private long countTodayByStatus(String status) {
        // 今日终态计数：updated_at 当日（走 idx_outreq_updated）
        return outboundRequestRepository.countTodayByStatus(status);
    }

    public record MonitorOverview(
            long todayCalls, double successRate,
            long todaySuccess, long todayDeadLetter,
            long compensating, long pendingRedelivery,
            long deadLetterBacklog, long unknown) {
    }
}
