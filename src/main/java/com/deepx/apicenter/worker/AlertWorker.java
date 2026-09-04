package com.deepx.apicenter.worker;

import com.deepx.apicenter.model.AlertRuleRow;
import com.deepx.apicenter.repository.AlertRuleRepository;
import com.deepx.apicenter.repository.CallLogRepository;
import com.deepx.apicenter.repository.DeadLetterRepository;
import com.deepx.apicenter.repository.InboundDeliveryRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.service.AlertService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 告警 worker（M4 交付，D-M4-5）：周期评估 alert_rule 四指标（数据源 = SQL 聚合，可离线单测）——
 * success_rate（近 5 分钟出站终态成功率，走 idx_outreq_updated）/ p99_latency（call_log 近 5 分钟 OUT 条，
 * idx_call_time + 最新 5000 条近似）/ dead_letter_backlog（PENDING 死信数）/ retry_backlog
 * （COMPENSATING + PENDING 积压）。命中 → 冷却判重 → alert_event 落库 + log.warn（通知渠道 v1.1）。
 * 同轮更新 apicenter.backlog Gauge（监控页与 Prometheus 共用）。
 */
@Component
public class AlertWorker {

    private static final Logger log = LoggerFactory.getLogger(AlertWorker.class);

    /** 指标统计窗口（分钟，与 metricName 口径一致） */
    private static final int METRIC_WINDOW_MINUTES = 5;

    private final AlertRuleRepository alertRuleRepository;
    private final AlertService alertService;
    private final OutboundRequestRepository outboundRequestRepository;
    private final InboundDeliveryRepository inboundDeliveryRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final CallLogRepository callLogRepository;

    /** 规则缓存（60s 刷新，避免每 30s 全表扫 alert_rule） */
    private final AtomicReference<List<AlertRuleRow>> ruleCache = new AtomicReference<>(List.of());
    private volatile long ruleCacheLoadedAt;

    /** 积压 Gauge 载体（MonitorController 读取注册 Micrometer Gauge） */
    private final AtomicReference<Map<String, Long>> backlog = new AtomicReference<>(Map.of());

    public AlertWorker(AlertRuleRepository alertRuleRepository,
                       AlertService alertService,
                       OutboundRequestRepository outboundRequestRepository,
                       InboundDeliveryRepository inboundDeliveryRepository,
                       DeadLetterRepository deadLetterRepository,
                       CallLogRepository callLogRepository,
                       MeterRegistry meterRegistry) {
        this.alertRuleRepository = alertRuleRepository;
        this.alertService = alertService;
        this.outboundRequestRepository = outboundRequestRepository;
        this.inboundDeliveryRepository = inboundDeliveryRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.callLogRepository = callLogRepository;
        // 积压 Gauge（D-M4-5）：每轮扫描更新 backlog 快照，Prometheus 抓取读同一份
        for (String kind : new String[]{"compensating", "pending", "dead_letter", "unknown"}) {
            meterRegistry.gauge("apicenter.backlog", Tags.of("kind", kind), backlog,
                    ref -> ref.get().getOrDefault(kind, 0L));
        }
    }

    @Scheduled(fixedDelayString = "${app.api-center.alert-worker-fixed-delay-ms:30000}")
    public void scan() {
        refreshRules();
        // 积压三元组（监控页与 apicenter.backlog Gauge 共用一份数据，避免重复聚合）
        long compensating = outboundRequestRepository.countByStatus("COMPENSATING");
        long pending = inboundDeliveryRepository.countByStatus("PENDING");
        long deadBacklog = deadLetterRepository.countPending();
        long unknown = outboundRequestRepository.countByStatus("UNKNOWN");
        backlog.set(Map.of(
                "compensating", compensating,
                "pending", pending,
                "dead_letter", deadBacklog,
                "unknown", unknown));
        if (ruleCache.get().isEmpty()) {
            return; // 无启用规则：仅更新积压（内置告警由 AlertService.recordVerifyFailure 直接触发）
        }
        for (AlertRuleRow rule : ruleCache.get()) {
            try {
                evaluate(rule, compensating, pending, deadBacklog);
            } catch (Exception e) {
                // 单规则异常隔离：一条规则失败不影响本轮其余规则
                log.error("告警规则 {} 评估失败，跳过", rule.id(), e);
            }
        }
    }

    private void evaluate(AlertRuleRow rule, long compensating, long pending, long deadBacklog) {
        switch (rule.metric()) {
            case "success_rate" -> {
                double rate = outboundRequestRepository.successRateRecent(METRIC_WINDOW_MINUTES);
                if (rate >= 0) { // -1 = 窗口内无终态样本，不做判断
                    alertService.evaluateAndFire(rule, rate);
                }
            }
            case "p99_latency" -> alertService.evaluateAndFire(rule,
                    callLogRepository.p99LatencyOut(METRIC_WINDOW_MINUTES));
            case "dead_letter_backlog" -> alertService.evaluateAndFire(rule, deadBacklog);
            case "retry_backlog" -> alertService.evaluateAndFire(rule, compensating + pending);
            default -> log.error("告警规则 {} 指标未知（{}），跳过", rule.id(), rule.metric());
        }
    }

    private void refreshRules() {
        long now = System.currentTimeMillis();
        if (now - ruleCacheLoadedAt > 60_000) {
            try {
                ruleCache.set(alertRuleRepository.findEnabled());
                ruleCacheLoadedAt = now;
            } catch (Exception e) {
                log.error("告警规则加载失败（沿用上次缓存）", e);
            }
        }
    }

    /** 最新积压快照（MonitorController 注册 Gauge 用） */
    public Map<String, Long> backlogSnapshot() {
        return backlog.get();
    }
}
