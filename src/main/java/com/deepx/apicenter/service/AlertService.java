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

    /** 调用方鉴权连续失败阈值（B4；默认 10） */
    @Value("${app.api-center.client-auth.fail-alert-threshold:10}")
    private int failAlertThreshold;

    private final AlertEventRepository alertEventRepository;

    /** 冷却判重（内存，单实例）：key = rule:<id> 或 verify:<appId> → 上次触发时刻 */
    private final Map<String, Long> lastFiredAt = new ConcurrentHashMap<>();

    /** 验签失败计数（内存滑动 5 分钟窗口近似：key=appId，值=[窗口起始秒, 计数]） */
    private final Map<String, long[]> verifyFailWindows = new ConcurrentHashMap<>();
    /** 调用方鉴权失败窗口（B4）：key = principal:<clientId> 或 ip:<addr> */
    private final Map<String, long[]> authFailWindows = new ConcurrentHashMap<>();

    /**
     * 失败窗口键数上限（v1.2）：主体可**自报** ⇒ 键值不可信，极端情况下（伪造随机 `X-Client-Id`）
     * 会把内存键刷爆；超过上限的新键统一折算到 `overflow` 桶（仍会累计并触发阈值告警，只是无法再细分主体）。
     */
    private static final int MAX_AUTH_FAIL_KEYS = 2000;

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
     * **调用方鉴权连续失败**告警（2026-09-23 B4，设计方案 §13.2）：与 {@link #recordVerifyFailure} 同模式
     * （5 分钟窗口、命中即上报并重置窗口），但**按主体**计数（主体未知时按来源 IP）——
     * 两条告警并行不冲突（一条按 app，一条按 principal）。
     */
    public void recordAuthFailure(String principal, String clientIp, String principalName, String lastErrorCode) {
        String key = principal == null || principal.isBlank() ? "ip:" + clientIp : "principal:" + principal;
        if (authFailWindows.size() >= MAX_AUTH_FAIL_KEYS && !authFailWindows.containsKey(key)) {
            // 防「自报主体」把键刷爆：新键折到同一个桶（本类关注的仍是「连续失败」这个事实）
            key = "overflow";
        }
        long windowSeconds = 300;
        long currentSecond = System.currentTimeMillis() / 1000;
        long[] window = authFailWindows.compute(key, (k, old) ->
                old == null || currentSecond - old[0] >= windowSeconds
                        ? new long[]{currentSecond, 0} : old);
        long count = ++window[1];
        if (count >= failAlertThreshold) {
            authFailWindows.remove(key);
            String who = principalName != null && !principalName.isBlank()
                    ? principalName + "（" + principal + "）" : "未识别主体";
            fire(null, "auth_fail_streak", "CRITICAL",
                    "调用方鉴权连续失败 " + count + " 次（5 分钟窗口）：" + who + " ip=" + clientIp
                            + "，最后错误码 " + lastErrorCode + "，疑似凭证错误或恶意探测",
                    "{\"principal\":\"" + (principal == null ? "" : principal) + "\",\"clientIp\":\""
                            + (clientIp == null ? "" : clientIp) + "\",\"count\":" + count
                            + ",\"errorCode\":\"" + (lastErrorCode == null ? "" : lastErrorCode) + "\"}");
        }
    }

    /**
     * 规则触发（AlertWorker 调用）：冷却期内（rule 维度）不重复落库。
     * threshold 解析 "&lt;op&gt; &lt;number&gt;"（op ∈ &lt; &lt;= &gt; &gt;=；metric 决定语义与单位），
     * 非法表达式返回 false（规则跳过不崩 worker，log.error 可观测）。
     */
    /**
     * **入站鉴权平台设置变更**留痕（2026-09-24 v1.2）：平台默认方式 / 强制自报主体的变更
     * **影响所有未绑定 `CLIENT_AUTH` 的接口**，因此「放松类」变更（方式变更 / `require_client_id` 1→0）
     * 落 `alert_event(metric=inbound_auth_setting_changed, level=WARN)` ——
     * 供运维追溯「谁在什么时候放宽了鉴权」（与鉴权失败告警同一张表，metric 区分）。
     */
    public void recordInboundAuthSettingChanged(String change, String operator) {
        fire(null, "inbound_auth_setting_changed", "WARN",
                "入站鉴权平台设置变更（操作者 " + operator + "）：" + change,
                "{\"operator\":\"" + operator + "\",\"change\":\"" + change + "\"}");
    }

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

    /**
     * 应用删除时清理验签失败窗口（2026-09-12）：键为 appId，原实现只在窗口过期时自清，
     * 删应用后会残留孤儿条目。
     */
    public void evictApp(String appId) {
        verifyFailWindows.remove(appId);
    }

    /**
     * 告警规则删除时清理其冷却记录（2026-09-18 修复）：
     * `evaluateAndFire` 写入的冷却键是 **`rule:<id>`**（见 :80），而原实现用 `endsWith("#" + ruleId)` 匹配
     * → **永不命中**，删规则后冷却条目残留（轻微内存泄漏 + 重建同 id 规则时冷却残留）。
     * 回归：AlertServiceTest#冷却期内不重复_evictRule后可立即再触发。
     */
    public void evictRule(long ruleId) {
        lastFiredAt.remove("rule:" + ruleId);
    }

    /** 测试支撑：清空冷却与验签计数 */
    public void reset() {
        lastFiredAt.clear();
        verifyFailWindows.clear();
    }
}
