package com.deepx.apicenter.mapping;

import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.exception.BizException;
import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.Options;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * condition 表达式沙箱（M0-02 §5，定稿 D10/D11）：
 * - 内核 Aviator 5（纯解释器、无反射/类加载，天然防注入）；
 * - 变量注入：入站顶层字段名直接作变量；含点号字段注入「点→下划线」别名
 *   （如 filter.seller_id → filter_seller_id）；同时注入嵌套 Map 供 a.b 语法访问；
 * - 硬限制：表达式长度 ≤ 500（与 param 列宽对齐）、MAX_LOOP_COUNT 防死循环；
 * - 结果必须 boolean，求值异常 → 50000。
 */
@Component
public class ConditionEvaluator {

    private static final int MAX_LENGTH = 500;
    private static final int MAX_FLATTEN_DEPTH = 8;

    private final AviatorEvaluatorInstance engine = AviatorEvaluator.newInstance();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    public ConditionEvaluator() {
        engine.setOption(Options.MAX_LOOP_COUNT, 1000);
    }

    public boolean eval(String expression, UnifiedModel inbound) {
        if (expression == null || expression.isBlank()) {
            throw BizException.fieldInvalid("condition 表达式不能为空");
        }
        if (expression.length() > MAX_LENGTH) {
            throw BizException.fieldInvalid("condition 表达式长度超限（≤" + MAX_LENGTH + "）");
        }
        try {
            Expression compiled = cache.computeIfAbsent(expression, e -> engine.compile(e, true));
            Object result = compiled.execute(buildEnv(inbound));
            if (!(result instanceof Boolean b)) {
                throw BizException.fieldInvalid("condition 表达式结果必须是布尔值：" + expression);
            }
            return b;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50000, "condition 表达式求值异常：" + e.getMessage());
        }
    }

    /** 变量环境：顶层标量直接绑定 + 含点字段下划线别名 + 嵌套 Map（M0-02 D11） */
    private Map<String, Object> buildEnv(UnifiedModel inbound) {
        Map<String, Object> env = new LinkedHashMap<>();
        if (inbound.root() instanceof UnifiedModel.ObjectNode obj) {
            obj.fields().forEach((k, v) -> {
                env.put(k, toJava(v));
                flattenAlias(k, v, env, 0);
            });
        }
        return env;
    }

    /** 递归展平叶子路径 → 下划线别名（深度受限防爆） */
    private void flattenAlias(String path, UnifiedModel.UNode node, Map<String, Object> env, int depth) {
        if (depth > MAX_FLATTEN_DEPTH) {
            return;
        }
        if (node instanceof UnifiedModel.ObjectNode obj) {
            obj.fields().forEach((k, v) -> flattenAlias(path + "_" + k, v, env, depth + 1));
        } else if (node instanceof UnifiedModel.ScalarNode s) {
            env.put(path, scalarValue(s));
        }
        // ARRAY 不做叶子展开（aggregate 处理）
    }

    /** UnifiedModel → Aviator 可消费的 Java 值（标量 + 嵌套 Map） */
    private Object toJava(UnifiedModel.UNode node) {
        return switch (node) {
            case UnifiedModel.ScalarNode s -> scalarValue(s);
            case UnifiedModel.ObjectNode obj -> {
                Map<String, Object> map = new LinkedHashMap<>();
                obj.fields().forEach((k, v) -> map.put(k, toJava(v)));
                yield map;
            }
            case UnifiedModel.ArrayNode arr -> arr.items().stream().map(this::toJava).toList();
        };
    }

    private Object scalarValue(UnifiedModel.ScalarNode s) {
        return s.value(); // String/Long/BigDecimal/Boolean/null 均被 Aviator 支持
    }
}
