package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.model.OutboundRequestStateLogRow;
import com.deepx.apicenter.model.ReconcileAuditRow;
import com.deepx.apicenter.repository.AlertEventRepository;
import com.deepx.apicenter.repository.CallLogRepository;
import com.deepx.apicenter.repository.DeadLetterRepository;
import com.deepx.apicenter.repository.InboundDeliveryRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.repository.ReconcileAuditRepository;
import com.deepx.apicenter.model.InterfaceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final InterfaceRepository interfaceRepository;

    /** UNKNOWN 超时自动降级时长（分钟，M0-03 §3.1；已有配置项，M4 消费） */
    @Value("${app.api-center.unknown-ttl-minutes:10}")
    private long unknownTtlMinutes;

    public MonitorService(OutboundRequestRepository outboundRequestRepository,
                          InboundDeliveryRepository inboundDeliveryRepository,
                          ReconcileAuditRepository reconcileAuditRepository,
                          DeadLetterRepository deadLetterRepository,
                          AlertEventRepository alertEventRepository,
                          CallLogRepository callLogRepository,
                          InterfaceRepository interfaceRepository) {
        this.outboundRequestRepository = outboundRequestRepository;
        this.inboundDeliveryRepository = inboundDeliveryRepository;
        this.reconcileAuditRepository = reconcileAuditRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.alertEventRepository = alertEventRepository;
        this.callLogRepository = callLogRepository;
        this.interfaceRepository = interfaceRepository;
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
            // 状态链：UNKNOWN → SUCCESS（RECONCILE_MANUAL，detail 带操作人 / 依据）
            outboundRequestRepository.transition(outboundRequestId, "SUCCESS", null, null, null, null,
                    OutboundRequestRepository.TRIGGER_RECONCILE_MANUAL,
                    "人工对账置为已到达（operator=" + operator + (reason == null || reason.isBlank() ? "" : "，依据=" + reason) + "）");
        } else {
            // 未到达：转补偿立即入队（重放携带同一 biz_id，去重依赖上游幂等，ADR 5）；
            // attempt 清零——首送预算已随 UNKNOWN 挂起消耗，不清零会被 worker 直接判死信（C3 缺陷修复）
            outboundRequestRepository.degradeUnknownToCompensating(outboundRequestId, LocalDateTime.now(),
                    OutboundRequestRepository.TRIGGER_RECONCILE_MANUAL,
                    "人工对账置为未到达（operator=" + operator + (reason == null || reason.isBlank() ? "" : "，依据=" + reason) + "）");
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
            // attempt 清零（同 reconcile COMPENSATING 分支：降级记录需新预算才有机会重放）
            outboundRequestRepository.degradeUnknownToCompensating(row.id(),
                    LocalDateTime.now().plusSeconds(TTL_RETRY_INTERVAL_SECONDS),
                    OutboundRequestRepository.TRIGGER_TTL_DOWNGRADE,
                    "UNKNOWN 超过 " + unknownTtlMinutes + " 分钟自动降级（重放依赖上游幂等，ADR 5）");
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

    // ---------- 统计增强（仪表盘/监控 v0.2：趋势 / TOP 接口 / 出站详情） ----------

    private static final long STATS_CACHE_TTL_MS = 60_000L;
    private static final int LATENCY_SAMPLE_LIMIT = 20_000;

    private final Map<String, CacheEntry<?>> statsCache = new ConcurrentHashMap<>();

    private record CacheEntry<T>(T value, long expireAt) {
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, java.util.function.Supplier<T> loader) {
        long now = System.currentTimeMillis();
        CacheEntry<?> hit = statsCache.get(key);
        if (hit != null && hit.expireAt() > now) {
            return (T) hit.value();
        }
        T fresh = loader.get();
        statsCache.put(key, new CacheEntry<>(fresh, now + STATS_CACHE_TTL_MS));
        return fresh;
    }

    private static long toEpochMinute(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toEpochSecond() / 60;
    }

    private record StatWindow(LocalDateTime from, LocalDateTime to, int stepMinutes) {
    }

    /** 时间窗归一：1h（5min 桶）/ 24h（30min 桶，默认）/ 7d（2h 桶） */
    private StatWindow statWindow(String range) {
        LocalDateTime to = LocalDateTime.now().withNano(0);
        if ("1h".equals(range)) {
            return new StatWindow(to.minusHours(1), to, 5);
        }
        if ("7d".equals(range)) {
            return new StatWindow(to.minusDays(7), to, 120);
        }
        return new StatWindow(to.minusHours(24), to, 30);
    }

    /** 趋势（仪表盘与监控总览共用，60s 缓存）：IN/OUT 流量 + 出站终态成败 + OUT 延迟分位 */
    public Trend trend(String range, String appId) {
        StatWindow w = statWindow(range);
        String cacheKey = "trend|" + w.stepMinutes() + "|" + (appId == null || appId.isBlank() ? "*" : appId);
        return cached(cacheKey, () -> buildTrend(w, appId));
    }

    private Trend buildTrend(StatWindow w, String appId) {
        LocalDateTime from = w.from(), to = w.to();
        Map<Long, Long> inByMin = callLogRepository.countByMinute("IN", appId, from, to);
        Map<Long, Long> outByMin = callLogRepository.countByMinute("OUT", appId, from, to);
        Map<Long, Map<String, Long>> termByMin = outboundRequestRepository.terminalCountsByMinute(from, to);
        List<CallLogRepository.OutLatencyRow> latRows = callLogRepository.outLatencies(from, to, LATENCY_SAMPLE_LIMIT);

        // 桶 = 窗口起点的 step 对齐分钟，逐桶汇总（空桶补 0）
        int step = w.stepMinutes();
        long fromMin = toEpochMinute(from);
        long toMin = toEpochMinute(to);
        long firstBucket = (fromMin / step) * step;
        Map<Long, int[]> agg = new LinkedHashMap<>();
        for (long b = firstBucket; b <= toMin; b += step) {
            agg.put(b, new int[6]); // in / out / success / dead / unknown / (latency 汇总见下)
        }
        mergeByBucket(inByMin, step, agg, 0);
        mergeByBucket(outByMin, step, agg, 1);
        for (Map.Entry<Long, Map<String, Long>> e : termByMin.entrySet()) {
            int[] slot = agg.get((e.getKey() / step) * step);
            if (slot == null) {
                continue;
            }
            Map<String, Long> m = e.getValue();
            slot[2] += m.getOrDefault("SUCCESS", 0L);
            slot[3] += m.getOrDefault("DEAD_LETTER", 0L);
            slot[4] += m.getOrDefault("UNKNOWN", 0L);
        }
        // 出站延迟分位（窗口内最新 N 条近似，桶内独立分位）
        Map<Long, List<Long>> latByBucket = new LinkedHashMap<>();
        for (CallLogRepository.OutLatencyRow r : latRows) {
            long b = (r.bucketMinute() / step) * step;
            if (!agg.containsKey(b)) {
                continue;
            }
            latByBucket.computeIfAbsent(b, k -> new ArrayList<>()).add(r.latencyMs());
        }
        List<TrendPoint> points = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : agg.entrySet()) {
            long bMin = e.getKey();
            int[] v = e.getValue();
            List<Long> lats = latByBucket.get(bMin);
            points.add(new TrendPoint(bMin * 60, v[0], v[1], v[2], v[3], v[4],
                    percentile(lats, 0.50), percentile(lats, 0.99)));
        }
        return new Trend(points, step, w.stepMinutes() == 5 ? "1h" : w.stepMinutes() == 120 ? "7d" : "24h");
    }

    private static void mergeByBucket(Map<Long, Long> minuteCounts, int step, Map<Long, int[]> agg, int idx) {
        for (Map.Entry<Long, Long> e : minuteCounts.entrySet()) {
            int[] slot = agg.get((e.getKey() / step) * step);
            if (slot != null) {
                slot[idx] += e.getValue();
            }
        }
    }

    private static long percentile(List<Long> latencies, double p) {
        if (latencies == null || latencies.isEmpty()) {
            return 0;
        }
        List<Long> sorted = latencies.stream().sorted().toList();
        int idx = (int) Math.ceil(sorted.size() * p) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    /** TOP 接口（窗口内流量/终态成败/延迟，60s 缓存）：按调用量降序截取 */
    public List<TopInterface> topInterfaces(String range, int limit) {
        StatWindow w = statWindow(range);
        int cap = Math.max(1, Math.min(limit <= 0 ? 8 : limit, 20));
        return cached("top|" + w.stepMinutes() + "|" + cap, () -> buildTop(w, cap));
    }

    private List<TopInterface> buildTop(StatWindow w, int cap) {
        Map<Long, Long> inByIface = callLogRepository.countInByInterface(w.from(), w.to());
        Map<Long, Map<String, Long>> termByIface =
                outboundRequestRepository.terminalCountsByInterface(w.from(), w.to());
        List<CallLogRepository.OutLatencyRow> latRows =
                callLogRepository.outLatencies(w.from(), w.to(), LATENCY_SAMPLE_LIMIT);
        Map<Long, List<Long>> latByIface = new LinkedHashMap<>();
        for (CallLogRepository.OutLatencyRow r : latRows) {
            if (r.interfaceId() != null) {
                latByIface.computeIfAbsent(r.interfaceId(), k -> new ArrayList<>()).add(r.latencyMs());
            }
        }
        List<InterfaceRow> metas = interfaceRepository.findAll(null, null, null, null, null);
        List<TopInterface> out = new ArrayList<>();
        for (InterfaceRow m : metas) {
            long inCalls = inByIface.getOrDefault(m.id(), 0L);
            Map<String, Long> terms = termByIface.getOrDefault(m.id(), Map.of());
            long success = terms.getOrDefault("SUCCESS", 0L);
            long dead = terms.getOrDefault("DEAD_LETTER", 0L);
            long unknown = terms.getOrDefault("UNKNOWN", 0L);
            List<Long> lats = latByIface.get(m.id());
            Double rate = success + dead == 0 ? null
                    : Math.round((double) success * 1000 / (success + dead)) / 10.0;
            out.add(new TopInterface(m.id(), m.code(), m.path(), m.appId(), m.appName(),
                    inCalls, success, dead, unknown, rate,
                    percentile(lats, 0.50), percentile(lats, 0.99)));
        }
        out.sort(Comparator.comparingLong(TopInterface::inCalls).reversed()
                .thenComparing((a, b) -> Long.compare(b.dead() + b.unknown(), a.dead() + a.unknown())));
        return out.size() <= cap ? out : out.subList(0, cap);
    }

    /** 出站记录详情（状态机 Tab：行信息 + payload 预览 + 状态链 + 对账审计时间线） */
    public OutboundDetail outboundDetail(long id) {
        OutboundRequestRow row = outboundRequestRepository.findById(id)
                .orElseThrow(() -> BizException.fieldInvalid("出站记录不存在：" + id));
        return new OutboundDetail(row.id(), row.interfaceId(), row.appId(), row.bizId(), row.status(),
                row.attemptCount(), row.maxAttempts(), row.errorCode(), row.traceId(),
                preview(row.inPayload()), preview(row.outPayload()), preview(row.respPayload()),
                str(row.nextRetryAt()), str(row.createdAt()), str(row.updatedAt()),
                stateChain(id), audits(id));
    }

    /** 状态链（M5 后状态链，设计 §4.6）：按 seq 升序返回完整流转历史 */
    public List<OutboundRequestStateLogRow> stateChain(long outboundRequestId) {
        return outboundRequestRepository.stateChain(outboundRequestId);
    }

    private static String str(LocalDateTime t) {
        return t == null ? null : t.toString();
    }

    private static String preview(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 4000 ? s : s.substring(0, 4000) + "…[truncated]";
    }

    // ---------- 统计增强返回模型 ----------

    public record TrendPoint(long ts, long inCalls, long outCalls, long outSuccess,
                             long outDead, long outUnknown, long p50, long p99) {
    }

    public record Trend(List<TrendPoint> buckets, int stepMinutes, String range) {
    }

    public record TopInterface(long interfaceId, String code, String path, String appId, String appName,
                               long inCalls, long success, long dead, long unknown,
                               Double successRate, long p50, long p99) {
    }

    public record OutboundDetail(long id, long interfaceId, String appId, String bizId, String status,
                                 int attemptCount, int maxAttempts, String errorCode, String traceId,
                                 String inPayloadPreview, String outPayloadPreview, String respPayloadPreview,
                                 String nextRetryAt, String createdAt, String updatedAt,
                                 List<OutboundRequestStateLogRow> stateChain,
                                 List<ReconcileAuditRow> audits) {
    }
}
