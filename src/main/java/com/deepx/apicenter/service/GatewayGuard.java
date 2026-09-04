package com.deepx.apicenter.service;

import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AppRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接入层防护（M4 交付，D-M4-6，补 M2 缺口）：QPS 限流 / 日配额 / IP 黑白名单。
 *
 * <p>语义定稿：
 * <ul>
 *   <li>位置：网关入口路由命中、应用启用校验之后、落 outbound_request 之前——
 *       拒绝不落运行表（不污染状态机，M2 测试点口径）；</li>
 *   <li>QPS：固定秒级窗口计数（交界突刺最坏 2×limit，保护语义达标；滑动窗口 v1.1）；</li>
 *   <li>日配额：按 (appId, yyyyMMdd Asia/Shanghai) 计数滚动重置；</li>
 *   <li>IP：精确 IP 文本匹配（CIDR 不支持，v1.1）；黑名单命中或白名单非空未命中 → 40103；
 *       来源地址默认 remoteAddr，trust-xff 开启后取 X-Forwarded-For 首值（生产反代后置 true，防伪造绕过）；</li>
 *   <li>单实例内存口径：QPS 窗口 / 日配额重启清零（日配额重启后重新计数，偏差可接受）；多实例 v1.1。</li>
 * </ul>
 */
@Component
public class GatewayGuard {

    private static final Logger log = LoggerFactory.getLogger(GatewayGuard.class);

    /** 42901 QPS 限流 / 42902 日配额超限 / 40103 来源 IP 被拒（401xx 鉴权段） */
    public static final int QPS_LIMITED = 42901;
    public static final int DAILY_QUOTA_EXCEEDED = 42902;
    public static final int IP_REJECTED = 40103;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 来源地址取 X-Forwarded-For 首值的开关（默认 false——防伪造 XFF 绕过白名单） */
    @Value("${app.api-center.trust-xff:false}")
    private boolean trustXff;

    /** QPS 固定窗口：appId → (窗口起始秒, 计数) */
    private final Map<String, long[]> qpsWindows = new ConcurrentHashMap<>();

    /** 日配额：appId → (日期 yyyy-MM-dd, 计数) */
    private final Map<String, Object[]> dailyCounters = new ConcurrentHashMap<>();

    /**
     * 网关入口校验：QPS → 日配额 → IP 黑白名单（顺序：先限流后 IP，限流命中即快速返回）。
     * 通过返回静默；拒绝抛 BizException（统一信封 HTTP 状态 = code/100：429/429/403）。
     */
    public void check(AppRow app, String clientIp) {
        checkQps(app);
        checkDailyQuota(app);
        checkIp(app, clientIp);
    }

    /** 客户端来源地址解析：默认 remoteAddr；trust-xff=true 取 X-Forwarded-For 首值 */
    public String resolveClientIp(String remoteAddr, String xForwardedFor) {
        if (trustXff && xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return remoteAddr;
    }

    private void checkQps(AppRow app) {
        Integer qpsLimit = app.qpsLimit();
        if (qpsLimit == null || qpsLimit <= 0) {
            return; // 空/0 = 不限
        }
        long currentSecond = System.currentTimeMillis() / 1000;
        // 窗口滚动与计数在同一次 compute 内完成（per-key 原子，并发请求不丢计数）
        long[] window = qpsWindows.compute(app.appId(), (k, old) -> {
            if (old == null || old[0] != currentSecond) {
                return new long[]{currentSecond, 1};
            }
            old[1]++;
            return old;
        });
        if (window[1] > qpsLimit) {
            log.warn("应用 {} QPS 限流（窗口计数 {} > 上限 {}）", app.appId(), window[1], qpsLimit);
            throw new BizException(QPS_LIMITED, "QPS 限流（上限 " + qpsLimit + " 次/秒）");
        }
    }

    private void checkDailyQuota(AppRow app) {
        Long dailyQuota = app.dailyQuota();
        if (dailyQuota == null || dailyQuota <= 0) {
            return; // 空/0 = 不限
        }
        String today = LocalDate.now(ZONE).toString();
        Object[] counter = dailyCounters.compute(app.appId(), (k, old) ->
                old == null || !today.equals(old[0]) ? new Object[]{today, new AtomicLong(0)} : old);
        long count = ((AtomicLong) counter[1]).incrementAndGet();
        if (count > dailyQuota) {
            log.warn("应用 {} 日配额超限（今日 {} > 上限 {}）", app.appId(), count, dailyQuota);
            throw new BizException(DAILY_QUOTA_EXCEEDED, "日调用量超限（上限 " + dailyQuota + " 次/天）");
        }
    }

    private void checkIp(AppRow app, String clientIp) {
        // 黑名单优先：命中即拒（无论白名单）
        if (matchesList(app.ipBlacklist(), clientIp)) {
            log.warn("应用 {} 来源 IP 命中黑名单 {}", app.appId(), clientIp);
            throw new BizException(IP_REJECTED, "来源 IP 被拒（黑名单）");
        }
        String whitelist = app.ipWhitelist();
        if (whitelist != null && !whitelist.isBlank() && !matchesList(whitelist, clientIp)) {
            log.warn("应用 {} 来源 IP {} 不在白名单", app.appId(), clientIp);
            throw new BizException(IP_REJECTED, "来源 IP 被拒（不在白名单）");
        }
    }

    /** 逗号分隔精确 IP 匹配（空白容忍；CIDR 不支持，v1.1） */
    private boolean matchesList(String csv, String ip) {
        if (csv == null || csv.isBlank() || ip == null) {
            return false;
        }
        for (String item : csv.split(",")) {
            if (ip.equals(item.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 测试支撑：清空限流 / 配额计数 */
    public void reset() {
        qpsWindows.clear();
        dailyCounters.clear();
    }
}
