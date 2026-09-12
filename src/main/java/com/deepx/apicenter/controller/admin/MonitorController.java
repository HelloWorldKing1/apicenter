package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.MonitorDtos.AlertRuleRequest;
import com.deepx.apicenter.dto.MonitorDtos.PagedResponse;
import com.deepx.apicenter.dto.MonitorDtos.ReconcileRequest;
import com.deepx.apicenter.model.AlertRuleRow;
import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.repository.AlertEventRepository;
import com.deepx.apicenter.repository.AlertRuleRepository;
import com.deepx.apicenter.repository.CallLogRepository;
import com.deepx.apicenter.repository.DeadLetterRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.service.MonitorService;
import com.deepx.apicenter.service.MonitorService.OutboundDetail;
import com.deepx.apicenter.service.MonitorService.TopInterface;
import com.deepx.apicenter.service.MonitorService.Trend;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 监控管理端点（M4 交付，设计 §4「接口监控」模块）：统计卡 / 调用日志 / UNKNOWN 对账 / 死信重放 /
 * 告警事件与规则。管理面前缀 /api/admin（统一信封 {code, msg, data}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/monitor")
public class MonitorController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** 关键字检索（url LIKE '%kw%'，无法走索引）的默认时间窗 */
    private static final Duration KEYWORD_DEFAULT_WINDOW = Duration.ofHours(24);
    /** 关键字检索允许的最大时间窗（超出按 7 天截断，避免全表扫描） */
    private static final Duration KEYWORD_MAX_WINDOW = Duration.ofDays(7);

    private final MonitorService monitorService;
    private final OutboundRequestRepository outboundRequestRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final AlertEventRepository alertEventRepository;
    private final AlertRuleRepository alertRuleRepository;
    private final com.deepx.apicenter.service.AlertService alertService;
    private final CallLogRepository callLogRepository;

    public MonitorController(MonitorService monitorService,
                             OutboundRequestRepository outboundRequestRepository,
                             DeadLetterRepository deadLetterRepository,
                             AlertEventRepository alertEventRepository,
                             AlertRuleRepository alertRuleRepository,
                             CallLogRepository callLogRepository,
                             com.deepx.apicenter.service.AlertService alertService) {
        this.monitorService = monitorService;
        this.outboundRequestRepository = outboundRequestRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.alertEventRepository = alertEventRepository;
        this.alertRuleRepository = alertRuleRepository;
        this.callLogRepository = callLogRepository;
        this.alertService = alertService;
    }

    // ---------- 统计卡（D-M4-5） ----------

    @GetMapping("/overview")
    public ApiResult<MonitorService.MonitorOverview> overview() {
        return monitorService.overview();
    }

    // ---------- 调用日志（D-M4-4 数据消费） ----------

    @GetMapping("/call-logs")
    public ApiResult<PagedResponse<CallLogRepository.CallLogView>> callLogs(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Long interfaceId,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String statusGroup,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) String timeFrom,
            @RequestParam(required = false) String timeTo,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int size = clampSize(pageSize);
        int offset = offset(page, size);
        // HTTP 码区间：statusCode 精确优先，否则 statusGroup（2xx/4xx/5xx）
        Integer min = null, max = null;
        if (statusCode != null) {
            min = statusCode;
            max = statusCode + 1;
        } else if (statusGroup != null && !statusGroup.isBlank()) {
            switch (statusGroup) {
                case "2xx" -> { min = 200; max = 300; }
                case "4xx" -> { min = 400; max = 500; }
                case "5xx" -> { min = 500; max = 600; }
                default -> throw com.deepx.apicenter.exception.BizException.fieldInvalid(
                        "statusGroup 仅支持 2xx/4xx/5xx");
            }
        }
        // 关键字走 `url LIKE '%kw%'`（索引无效）→ 强制有界时间窗：缺省近 24h，最长 7 天（2026-09-12）
        LocalDateTime[] window = boundedWindow(parseTime(timeFrom), parseTime(timeTo),
                keyword != null && !keyword.isBlank());
        return ApiResult.ok(PagedResponse.of(
                callLogRepository.findPaged(traceId, interfaceId, direction, appId, min, max,
                        window[0], window[1], keyword, offset, size),
                callLogRepository.count(traceId, interfaceId, direction, appId, min, max,
                        window[0], window[1], keyword),
                page, size));
    }

    /** 调用日志详情（含 req_headers / req_body / resp_body 全量；列表已瘦身不再带 body） */
    @GetMapping("/call-logs/{id}")
    public ApiResult<CallLogRepository.CallLogView> callLogDetail(@PathVariable long id) {
        return ApiResult.ok(callLogRepository.findById(id)
                .orElseThrow(() -> com.deepx.apicenter.exception.BizException.fieldInvalid(
                        "调用日志不存在：" + id)));
    }

    /**
     * 关键字检索的时间窗约束：未传 → 近 24h；跨度超 7 天 → 截断为近 7 天（保留 end）。
     * 仅 affecting keyword 查询，其他过滤仍按调用方传入的时间窗。
     */
    private LocalDateTime[] boundedWindow(LocalDateTime from, LocalDateTime to, boolean keywordPresent) {
        if (!keywordPresent) {
            return new LocalDateTime[]{from, to};
        }
        LocalDateTime end = to != null ? to : LocalDateTime.now();
        LocalDateTime start = from != null ? from : end.minus(KEYWORD_DEFAULT_WINDOW);
        if (Duration.between(start, end).compareTo(KEYWORD_MAX_WINDOW) > 0) {
            log.warn("调用日志关键字检索时间窗超过 7 天，已按近 7 天截断（url LIKE 无法走索引）");
            start = end.minus(KEYWORD_MAX_WINDOW);
        }
        return new LocalDateTime[]{start, end};
    }

    /** 时间参数解析：ISO 本地时间（yyyy-MM-ddTHH:mm:ss）或带空格的日期时间 */
    private static LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim();
        try {
            return LocalDateTime.parse(v);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e2) {
                throw com.deepx.apicenter.exception.BizException.fieldInvalid("时间参数格式非法：" + v);
            }
        }
    }

    // ---------- 统计增强（仪表盘 / 监控 v0.2） ----------

    /** 趋势（1h/24h/7d 分桶；仪表盘与监控总览共用，60s 缓存） */
    @GetMapping("/stats/trend")
    public ApiResult<Trend> trend(@RequestParam(defaultValue = "24h") String range,
                                  @RequestParam(required = false) String appId) {
        return ApiResult.ok(monitorService.trend(range, appId));
    }

    /** TOP 接口（窗口内调用量降序截取；列含终态成败与延迟分位） */
    @GetMapping("/stats/top-interfaces")
    public ApiResult<List<TopInterface>> topInterfaces(
            @RequestParam(defaultValue = "24h") String range,
            @RequestParam(defaultValue = "8") int limit) {
        return ApiResult.ok(monitorService.topInterfaces(range, limit));
    }

    /** 出站记录详情（状态机 Tab：payload 预览 + 对账审计时间线） */
    @GetMapping("/outbound-requests/{id}")
    public ApiResult<OutboundDetail> outboundDetail(@PathVariable long id) {
        return ApiResult.ok(monitorService.outboundDetail(id));
    }

    // ---------- UNKNOWN 对账（D-M4-2） ----------

    @GetMapping("/outbound-requests")
    public ApiResult<PagedResponse<OutboundRequestRow>> outboundRequests(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String bizId,
            @RequestParam(required = false) String traceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int size = clampSize(pageSize);
        int offset = offset(page, size);
        return ApiResult.ok(PagedResponse.of(
                outboundRequestRepository.findPaged(status, bizId, traceId, offset, size),
                outboundRequestRepository.countPaged(status, bizId, traceId), page, size));
    }

    /** 人工对账置位：target ∈ SUCCESS（已到达）/ COMPENSATING（未到达，立即入补偿队列） */
    @PostMapping("/outbound-requests/{id}/reconcile")
    public ApiResult<OutboundRequestRow> reconcile(@PathVariable long id,
                                                   @Valid @RequestBody ReconcileRequest req) {
        return ApiResult.ok(monitorService.reconcile(id, req.target(), req.operator(), req.reason()));
    }

    /** 对账审计轨迹（一个 UNKNOWN 可能先 TTL 降级再人工修正，全历史保留） */
    @GetMapping("/outbound-requests/{id}/audits")
    public ApiResult<List<com.deepx.apicenter.model.ReconcileAuditRow>> audits(@PathVariable long id) {
        return ApiResult.ok(monitorService.audits(id));
    }

    // ---------- 死信（D-M4-3） ----------
    @GetMapping("/dead-letters")
    public ApiResult<PagedResponse<DeadLetterRepository.DeadLetterView>> deadLetters(
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int size = clampSize(pageSize);
        int offset = offset(page, size);
        return ApiResult.ok(PagedResponse.of(
                deadLetterRepository.findPaged(bizType, status, offset, size),
                deadLetterRepository.count(bizType, status), page, size));
    }

    /** 死信重放 = 重新入队（状态重置后由补偿 worker 自然重放 / 重送） */
    @PostMapping("/dead-letters/{id}/replay")
    public ApiResult<Void> replayDeadLetter(@PathVariable long id) {
        monitorService.replayDeadLetter(id);
        return ApiResult.ok();
    }

    // ---------- 告警（D-M4-5） ----------

    @GetMapping("/alerts")
    public ApiResult<PagedResponse<com.deepx.apicenter.model.AlertEventRow>> alerts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int size = clampSize(pageSize);
        int offset = offset(page, size);
        return ApiResult.ok(PagedResponse.of(
                alertEventRepository.findPaged(offset, size),
                alertEventRepository.count(), page, size));
    }

    @GetMapping("/alert-rules")
    public ApiResult<List<AlertRuleRow>> alertRules() {
        return ApiResult.ok(alertRuleRepository.findAll());
    }

    @PostMapping("/alert-rules")
    public ApiResult<Void> createAlertRule(@Valid @RequestBody AlertRuleRequest req) {
        validateRule(req);
        alertRuleRepository.insert(req.name(), req.metric(), req.threshold(), req.notifyChannel(), req.enabled());
        return ApiResult.ok();
    }

    @PutMapping("/alert-rules/{id}")
    public ApiResult<Void> updateAlertRule(@PathVariable long id, @Valid @RequestBody AlertRuleRequest req) {
        validateRule(req);
        alertRuleRepository.update(id, req.name(), req.metric(), req.threshold(), req.notifyChannel(), req.enabled());
        return ApiResult.ok();
    }

    @DeleteMapping("/alert-rules/{id}")
    public ApiResult<Void> deleteAlertRule(@PathVariable long id) {
        alertRuleRepository.delete(id);
        alertService.evictRule(id);   // 清理该规则的冷却记录（2026-09-12）
        return ApiResult.ok();
    }

    /** 规则校验：metric 白名单 + threshold 表达式格式（无副作用——试评估会真实触发告警，禁止用于校验） */
    private void validateRule(AlertRuleRequest req) {
        if (!List.of("success_rate", "p99_latency", "dead_letter_backlog", "retry_backlog").contains(req.metric())) {
            throw com.deepx.apicenter.exception.BizException.fieldInvalid(
                    "指标仅允许 success_rate / p99_latency / dead_letter_backlog / retry_backlog");
        }
        if (!thresholdParsable(req.threshold())) {
            throw com.deepx.apicenter.exception.BizException.fieldInvalid(
                    "阈值表达式非法（期望形如 \"> 100\" / \"< 95\"）：" + req.threshold());
        }
    }

    private boolean thresholdParsable(String threshold) {
        if (threshold == null) {
            return false;
        }
        String t = threshold.trim();
        for (String op : new String[]{">=", "<=", ">", "<"}) {
            if (t.startsWith(op)) {
                try {
                    Double.parseDouble(t.substring(op.length()).trim());
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return false;
    }

    private int clampSize(int pageSize) {
        return Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
    }

    private int offset(int page, int size) {
        return Math.max(0, (Math.max(1, page) - 1) * size);
    }
}
