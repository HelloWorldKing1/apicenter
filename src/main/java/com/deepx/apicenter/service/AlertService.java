package com.deepx.apicenter.service;

import com.deepx.apicenter.model.AlertRuleRow;
import com.deepx.apicenter.repository.AlertEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警服务（M4 交付，D-M4-5）：告警事件落库 + 冷却判重 + 验签连续失败计数（内置告警）。
 * 通知渠道（notify_channel：邮件 / IM）首期仅随事件记录——SMTP / webhook 对接 v1.1（范围纪律）。
 */
@Component
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** 规则命中后的冷却期分钟数（防 worker 每 30s 一轮刷屏） */
    @Value("${app.api-center.alert-cooldown-minutes:5}")
    private long cooldownMinutes;

    /** 验签连续失败内置告警阈值（5 分钟窗口内同应用失败次数，设计 §5.3「连续失败告警」） */
    @Value("${app.api-center.verify-fail-alert-threshold:10}")
    private int verifyFailThreshold;

    private final AlertEventRepository alertEventRepository;

    /** 冷却判重（内存，单实例）：key = rule:<id> 或 verify:<appId> → 上次触发时刻 */
    private final Map<String, Long> lastFiredAt = new ConcurrentHashMap<>();

    /** 验签失败计数（内存滑动 5 分钟窗口近似：key=appId，值=[窗口起始秒, 计数]） */
    private final Map<String, long[]> verifyFailWindows = new ConcurrentHashMap<>();

    public AlertService(AlertEventRepository alertEventRepository) {
        this.alertEventRepository = alertEventRepository;
    }

    /** 验签失败上报（HmacCallbackVerifyAdapter 验签失败路径调用；5 分钟窗口计数） */
    public void recordVerifyFailure(String appId) {
        long windowSeconds = 300;
        long currentSecond = System.currentTimeMillis() / 1000;
        long[] window = verifyFailWindows.compute(appId, (k, old) ->
                old == null || currentSecond - old[0] >= windowSeconds
                        ? new long[]{currentSecond, 0} : old);
        long count = ++window[1];
        if (count >= verifyFailThreshold) {
            // 内置告警（rule_id=NULL）：命中阈值即上报并重置窗口（下个窗口重新累计）
            verifyFailWindows.remove(appId);
            fire(null, "verify_fail_streak", "CRITICAL",
                    "应用 " + appId + " 回调验签连续失败 " + count + " 次（5 分钟窗口），疑似凭证错误或恶意探测",
                    "{\"appId\":\"" + appId + "\",\"count\":" + count + "}");
        }
    }

    /**
     * 规则触发（AlertWorker 调用）：冷却期内（rule 维度）不重复落库。
     * threshold 解析 "&lt;op&gt; &lt;number&gt;"（op ∈ &lt; &lt;= &gt; &gt;=；metric 决定语义与单位），
     * 非法表达式返回 false（规则跳过不崩 worker，log.error 可观测）。
     */
    public boolean evaluateAndFire(AlertRuleRow rule, double metricValue) {
        Double threshold = parseThreshold(rule.threshold());
        if (threshold == null) {
            log.error("告警规则 {} 阈值表达式非法（期望 \"> 100\" 形如 <op> <number>），跳过：{}", rule.id(), rule.threshold());
            return false;
        }
        boolean hit = switch (opOf(rule.threshold())) {
            case "<" -> metricValue < threshold;
            case "<=" -> metricValue <= threshold;
            case ">" -> metricValue > threshold;
            case ">=" -> metricValue >= threshold;
            default -> false;
        };
        if (!hit) {
            return false;
        }
        String cooldownKey = "rule:" + rule.id();
        Long last = lastFiredAt.get(cooldownKey);
        if (last != null && System.currentTimeMillis() - last < cooldownMinutes * 60_000) {
            return false; // 冷却期内不重复
        }
        lastFiredAt.put(cooldownKey, System.currentTimeMillis());
        alertEventRepository.insert(rule.id(), rule.metric(), levelOf(rule.metric()),
                "告警 [" + rule.name() + "]：" + metricName(rule.metric()) + " 当前值 " + round(metricValue)
                        + "，命中阈值 " + rule.threshold()
                        + (rule.notifyChannel() != null && !rule.notifyChannel().isBlank()
                        ? "（通知渠道：" + rule.notifyChannel() + "，渠道对接 v1.1）" : ""),
                "{\"metric\":\"" + rule.metric() + "\",\"value\":" + round(metricValue)
                        + ",\"threshold\":\"" + rule.threshold() + "\"}");
        log.warn("告警触发 rule={}（{} {}，当前 {}）", rule.id(), rule.metric(), rule.threshold(), round(metricValue));
        return true;
    }

    /** 内置告警触发（冷却按 app 维度 5 分钟） */
    private void fire(Long ruleId, String metric, String level, String message, String context) {
        alertEventRepository.insert(ruleId, metric, level, message, context);
        log.warn("内置告警：{}", message);
    }

    private String opOf(String threshold) {
        if (threshold == null) {
            return "";
        }
        String t = threshold.trim();
        if (t.startsWith(">=")) {
            return ">=";
        }
        if (t.startsWith("<=")) {
            return "<=";
        }
        if (t.startsWith(">")) {
            return ">";
        }
        if (t.startsWith("<")) {
            return "<";
        }
        return "";
    }

    private Double parseThreshold(String threshold) {
        if (threshold == null) {
            return null;
        }
        String t = threshold.trim();
        String op = opOf(t);
        if (op.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(t.substring(op.length()).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String metricName(String metric) {
        return switch (metric == null ? "" : metric) {
            case "success_rate" -> "近 5 分钟出站终态成功率（%）";
            case "p99_latency" -> "近 5 分钟出站调用 P99 延迟（ms）";
            case "dead_letter_backlog" -> "死信 PENDING 堆积（条）";
            case "retry_backlog" -> "待重试积压 COMPENSATING+PENDING（条）";
            default -> metric;
        };
    }

    private String levelOf(String metric) {
        // 成功率下降与死信堆积为严重级；延迟与积压为警告级（可按需调整）
        return "success_rate".equals(metric) || "dead_letter_backlog".equals(metric) ? "CRITICAL" : "WARN";
    }

    private double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** 测试支撑：清空冷却与验签计数 */
    public void reset() {
        lastFiredAt.clear();
        verifyFailWindows.clear();
    }
}
