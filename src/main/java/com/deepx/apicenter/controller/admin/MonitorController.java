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
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 监控管理端点（M4 交付，设计 §4「接口监控」模块）：统计卡 / 调用日志 / UNKNOWN 对账 / 死信重放 /
 * 告警事件与规则。管理面前缀 /api/admin（统一信封 {code, msg, data}）。
 */
@RestController
@RequestMapping("/api/admin/monitor")
public class MonitorController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final MonitorService monitorService;
    private final OutboundRequestRepository outboundRequestRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final AlertEventRepository alertEventRepository;
    private final AlertRuleRepository alertRuleRepository;
    private final CallLogRepository callLogRepository;

    public MonitorController(MonitorService monitorService,
                             OutboundRequestRepository outboundRequestRepository,
                             DeadLetterRepository deadLetterRepository,
                             AlertEventRepository alertEventRepository,
                             AlertRuleRepository alertRuleRepository,
                             CallLogRepository callLogRepository) {
        this.monitorService = monitorService;
        this.outboundRequestRepository = outboundRequestRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.alertEventRepository = alertEventRepository;
        this.alertRuleRepository = alertRuleRepository;
        this.callLogRepository = callLogRepository;
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
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int size = clampSize(pageSize);
        int offset = offset(page, size);
        return ApiResult.ok(PagedResponse.of(
                callLogRepository.findPaged(traceId, interfaceId, offset, size),
                callLogRepository.count(traceId, interfaceId), page, size));
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
