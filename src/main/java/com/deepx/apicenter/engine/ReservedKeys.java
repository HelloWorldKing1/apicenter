package com.deepx.apicenter.engine;

import java.util.LinkedHashMap;

/**
 * 链内保留键（前置接口编排专用，见《前置接口编排设计方案》§6.3）。
 *
 * <p>{@code steps} 是宿主的**保留命名空间**：前置步骤的输出统一挂在
 * {@code payload.steps.<stepCode>.<field>}，供宿主的字段映射与 condition 引用
 * （如 {@code source=steps.auth.token}）。
 *
 * <p>两道剥离（都是必须的，缺一就会把数据发给不该发的一方）：
 * <ol>
 *   <li><b>前置入参剥离</b>（{@link #withoutSteps}）：宿主把模型交给前置接口 B 时先摘掉 steps，
 *       否则 B 若是「透传」配置，会把宿主的步骤输出一并发给 B 的供应商；</li>
 *   <li><b>宿主 ENCODE 前剥离</b>（{@link #stripSteps}）：宿主自己的映射为空（透传）时，
 *       steps 子树会被原样编进出站报文发给第三方。</li>
 * </ol>
 *
 * <p>写入一律用 {@link #putStep}（{@code ObjectNode.fields().put(字面量键)}）——
 * **绝不能**走 {@code UnifiedModel.set("steps.b.a.b")} 这种点路径解析：B 的字段名里若含
 * {@code .} 会被拆成多层路径，导致命名空间错位。
 */
public final class ReservedKeys {

    /** 保留字段名（宿主 IN 侧参数名与映射 target 名均禁止使用，保存期校验拦截） */
    public static final String STEPS = "steps";

    private ReservedKeys() {
    }

    /** 是否含保留键（宿主是否已合并过步骤输出） */
    public static boolean hasSteps(UnifiedModel model) {
        return model.root() instanceof UnifiedModel.ObjectNode obj && obj.fields().containsKey(STEPS);
    }

    /**
     * 返回不含保留键的模型视图（**浅隔离**：复用同一子节点，仅换根对象）：
     * 用于把宿主模型交给前置接口 —— B 看不到宿主的步骤输出。
     * 非对象根 / 无保留键时原样返回（不复制，零开销）。
     */
    public static UnifiedModel withoutSteps(UnifiedModel model) {
        if (!(model.root() instanceof UnifiedModel.ObjectNode obj) || !obj.fields().containsKey(STEPS)) {
            return model;
        }
        LinkedHashMap<String, UnifiedModel.UNode> copy = new LinkedHashMap<>(obj.fields());
        copy.remove(STEPS);
        return UnifiedModel.of(new UnifiedModel.ObjectNode(copy, obj.attributes()));
    }

    /** 原地摘掉保留键（宿主 ENCODE 前调用；仅在有前置步骤时由装配期守卫触发） */
    public static void stripSteps(UnifiedModel model) {
        if (model.root() instanceof UnifiedModel.ObjectNode obj) {
            obj.fields().remove(STEPS);
        }
    }

    /**
     * 写入一步的输出：{@code steps.<stepCode> = node}（steps 不存在则建）。
     * 字面量键写入，不做点路径解析。
     */
    public static UnifiedModel.ObjectNode putStep(UnifiedModel model, String stepCode,
                                                  UnifiedModel.UNode node) {
        UnifiedModel.ObjectNode root;
        if (model.root() instanceof UnifiedModel.ObjectNode obj) {
            root = obj;
        } else {
            root = UnifiedModel.ObjectNode.of();
            model.root(root);
        }
        UnifiedModel.UNode stepsNode = root.fields().get(STEPS);
        UnifiedModel.ObjectNode stepsObj;
        if (stepsNode instanceof UnifiedModel.ObjectNode existing) {
            stepsObj = existing;
        } else {
            stepsObj = UnifiedModel.ObjectNode.of();
            root.fields().put(STEPS, stepsObj);
        }
        stepsObj.fields().put(stepCode, node);
        return stepsObj;
    }
}
