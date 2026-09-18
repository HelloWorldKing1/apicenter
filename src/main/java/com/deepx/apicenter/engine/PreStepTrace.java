package com.deepx.apicenter.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 前置步骤运行留痕（前置编排 PS-6「留痕通道 3」）：供管理面 {@code POST /interfaces/{id}/test}
 * 的 {@code chainTrace.steps[]} 展示「本次真实走了哪几步、每步 HTTP 码 / 耗时 / 结局」。
 *
 * <p>与 {@code CallLogContext} 同款纪律，但按 **traceId 分桶**（无需显式 begin/clear）：
 * <ol>
 *   <li>{@link #add} 发现 traceId 变化即**替换**整个桶 —— 线程池复用时不会跨请求串账，
 *       也不会无限增长（桶最多保留一个请求的步骤，且条数有 {@value #MAX_ITEMS} 上限）；</li>
 *   <li>管理面调试端点在自己的 finally 里 {@link #drain}（读到即清理）；</li>
 *   <li>网关切面路径不读该桶 —— 由下一个请求的 {@code add} 覆盖，内存占用有界。</li>
 * </ol>
 */
public final class PreStepTrace {

    /** 单请求最多留痕条数（步数上限 5，留出重放冗余） */
    private static final int MAX_ITEMS = 20;

    private static final ThreadLocal<Bucket> HOLDER = new ThreadLocal<>();

    private PreStepTrace() {
    }

    /** 单步留痕：步骤名 / 目标接口 code / 失败策略 / HTTP 码 / 耗时 / 结局（SUCCESS 或错误码） */
    public record Item(String stepCode, String targetCode, String policy,
                       int httpStatus, long latencyMs, String outcome) {
    }

    private record Bucket(String traceId, List<Item> items) {
    }

    /** 追加一步留痕（traceId 变化即开新桶） */
    public static void add(String traceId, Item item) {
        Bucket bucket = HOLDER.get();
        if (bucket == null || !Objects.equals(bucket.traceId(), traceId)) {
            bucket = new Bucket(traceId, new ArrayList<>());
            HOLDER.set(bucket);
        }
        if (bucket.items().size() < MAX_ITEMS) {
            bucket.items().add(item);
        }
    }

    /** 取走指定 traceId 的留痕（读后清理；traceId 不匹配返回空列表） */
    public static List<Item> drain(String traceId) {
        Bucket bucket = HOLDER.get();
        if (bucket == null || !Objects.equals(bucket.traceId(), traceId)) {
            return List.of();
        }
        HOLDER.remove();
        return List.copyOf(bucket.items());
    }

    /** 测试支撑：清空 */
    public static void clear() {
        HOLDER.remove();
    }
}
