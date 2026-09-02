package com.deepx.apicenter.mapping;

import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.InterfaceRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 动态字段映射引擎（M0-02 规范落地）：
 * - 链上固定步骤（M0-01 D3）：读接口级 interface_field_mapping 按 sort_order 升序执行；
 * - 出站组装（D3）：规则为空 → 整体透传；非空 → 白名单（仅 target 命中字段）；
 * - 规则间无可见性（D2）：source 一律读入站侧原始模型；
 * - null 触发（D5）：source 不存在或为 NULL 标量即按 null_strategy 处理，不执行 op 本体；
 * - 6 操作语义见 M0-02 §4；转换失败按 null_strategy 兜底（D7）。
 */
@Component
public class MappingEngine {

    private final InterfaceRepository interfaceRepository;
    private final ConditionEvaluator conditionEvaluator;

    public MappingEngine(InterfaceRepository interfaceRepository, ConditionEvaluator conditionEvaluator) {
        this.interfaceRepository = interfaceRepository;
        this.conditionEvaluator = conditionEvaluator;
    }

    /** 链上固定步骤（MAPPING 阶段）：应用规则并把结果替换为链内 payload */
    public AdapterContext apply(AdapterContext ctx) {
        UnifiedModel result = apply(ctx.payload(), interfaceRepository.findMappings(ctx.iface().id()));
        ctx.payload().root(result.root());
        return ctx;
    }

    /** 核心执行：入站模型 + 规则 → 出站模型（空规则 = 透传，非空 = 白名单） */
    public UnifiedModel apply(UnifiedModel inbound, List<InterfaceRow.MappingRow> rules) {
        if (rules == null || rules.isEmpty()) {
            return inbound;
        }
        UnifiedModel out = UnifiedModel.emptyObject();
        List<InterfaceRow.MappingRow> sorted = rules.stream()
                .sorted(Comparator.comparing(InterfaceRow.MappingRow::sortOrder))
                .toList();
        for (InterfaceRow.MappingRow rule : sorted) {
            execute(inbound, out, rule);
        }
        return out;
    }

    // ---------- 单规则执行 ----------

    private void execute(UnifiedModel inbound, UnifiedModel out, InterfaceRow.MappingRow rule) {
        String op = rule.op();
        // default 常量注入：无条件写 param，不受 null 触发影响（M0-02 §4.4）
        if ("default".equals(op)) {
            out.set(rule.target(), UnifiedModel.ScalarNode.str(rule.param() == null ? "" : rule.param()));
            return;
        }
        // null 触发：source 不存在或 NULL → 不执行 op 本体（M0-02 D5）
        Optional<UnifiedModel.UNode> src = inbound.get(rule.source());
        if (src.isEmpty()) {
            applyNullStrategy(out, rule);
            return;
        }
        try {
            switch (op) {
                case "rename" -> out.set(rule.target(), src.get());
                case "typeCast" -> out.set(rule.target(), typeCast(src.get(), rule.param()));
                case "enumMap" -> out.set(rule.target(), enumMap(src.get(), rule.param()));
                case "condition" -> {
                    if (conditionEvaluator.eval(rule.param(), inbound)) {
                        out.set(rule.target(), src.get());
                    }
                }
                case "aggregate" -> aggregate(inbound, out, rule);
                default -> throw BizException.fieldInvalid("非法映射操作：" + op);
            }
        } catch (TypeRegistry.TypeCastException e) {
            // 转换失败按 null_strategy（M0-02 D7）；KEEP 语义 = 写源值原样（与 null 触发的 KEEP 写 null 区分）
            if ("KEEP".equals(rule.nullStrategy())) {
                out.set(rule.target(), src.get());
            } else {
                applyNullStrategy(out, rule);
            }
        }
    }

    // ---------- 6 操作 ----------

    /** typeCast（M0-02 §4.2）：经 TypeRegistry 转换矩阵；DATE 落 STRING(ISO) */
    private UnifiedModel.UNode typeCast(UnifiedModel.UNode src, String targetType) {
        if (!TypeRegistry.isKnown(targetType)) {
            throw BizException.fieldInvalid("typeCast 目标类型非法：" + targetType);
        }
        if (!(src instanceof UnifiedModel.ScalarNode s)) {
            throw new TypeRegistry.TypeCastException("typeCast 仅支持标量源");
        }
        Object converted = TypeRegistry.cast(s.value(), targetType);
        return switch (targetType) {
            case TypeRegistry.STRING -> UnifiedModel.ScalarNode.str((String) converted);
            case TypeRegistry.INT -> UnifiedModel.ScalarNode.num((Long) converted);
            case TypeRegistry.DECIMAL -> UnifiedModel.ScalarNode.decimal((BigDecimal) converted);
            case TypeRegistry.BOOL -> UnifiedModel.ScalarNode.bool((Boolean) converted);
            case TypeRegistry.DATE -> UnifiedModel.ScalarNode.str(
                    ((java.time.LocalDateTime) converted).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
            default -> throw new IllegalStateException("unreachable");
        };
    }

    /** enumMap（M0-02 §4.3）：param 语法 K1→V1, K2→V2；值省略=直通；未命中=原值透传（D8） */
    private UnifiedModel.UNode enumMap(UnifiedModel.UNode src, String param) {
        Map<String, String> table = new LinkedHashMap<>();
        for (String pair : param.split(",")) {
            String[] kv = pair.trim().split("→");
            if (kv.length >= 1 && !kv[0].isBlank()) {
                table.put(kv[0].trim(), kv.length >= 2 ? kv[1].trim() : kv[0].trim());
            }
        }
        String key;
        if (src instanceof UnifiedModel.ScalarNode s) {
            key = String.valueOf(s.value());
        } else {
            throw new TypeRegistry.TypeCastException("enumMap 仅支持标量源");
        }
        String mapped = table.get(key);
        return mapped == null ? src : UnifiedModel.ScalarNode.str(mapped); // 未命中透传（D8）
    }

    /** aggregate（M0-02 §4.6）：SUM/MAX/MIN/CONCAT[:sep]；source 须为 ARRAY；空数组视为 null */
    private void aggregate(UnifiedModel inbound, UnifiedModel out, InterfaceRow.MappingRow rule) {
        Optional<UnifiedModel.UNode> src = inbound.get(rule.source());
        if (src.isEmpty() || !(src.get() instanceof UnifiedModel.ArrayNode arr)) {
            throw new TypeRegistry.TypeCastException("aggregate 源必须为数组：" + rule.source());
        }
        if (arr.items().isEmpty()) {
            applyNullStrategy(out, rule); // 空数组视为 null（D9）
            return;
        }
        String param = rule.param();
        if ("SUM".equals(param)) {
            boolean allInt = true;
            BigDecimal sum = BigDecimal.ZERO;
            for (UnifiedModel.UNode item : arr.items()) {
                BigDecimal n = toNumber(item);
                sum = sum.add(n);
                allInt = allInt && item instanceof UnifiedModel.ScalarNode s && s.type() == UnifiedModel.ScalarType.INT;
            }
            out.set(rule.target(), allInt
                    ? UnifiedModel.ScalarNode.num(sum.longValue())
                    : UnifiedModel.ScalarNode.decimal(sum));
            return;
        }
        if ("MAX".equals(param) || "MIN".equals(param)) {
            UnifiedModel.UNode best = null;
            for (UnifiedModel.UNode item : arr.items()) {
                if (best == null || compare(item, best, "MAX".equals(param)) > 0) {
                    best = item;
                }
            }
            out.set(rule.target(), best);
            return;
        }
        if (param != null && param.startsWith("CONCAT")) {
            String sep = param.length() > 6 ? param.substring(7) : ""; // CONCAT:xxx
            StringBuilder sb = new StringBuilder();
            for (UnifiedModel.UNode item : arr.items()) {
                sb.append(stringify(item));
                if (sep != null && !sep.isEmpty()) {
                    sb.append(sep);
                }
            }
            String result = sep == null || sep.isEmpty() ? sb.toString()
                    : sb.substring(0, sb.length() - sep.length());
            out.set(rule.target(), UnifiedModel.ScalarNode.str(result));
            return;
        }
        throw BizException.fieldInvalid("aggregate 参数非法：" + param + "（SUM/MAX/MIN/CONCAT[:sep]）");
    }

    private BigDecimal toNumber(UnifiedModel.UNode node) {
        if (node instanceof UnifiedModel.ScalarNode s) {
            if (s.value() instanceof Long l) {
                return BigDecimal.valueOf(l);
            }
            if (s.value() instanceof BigDecimal d) {
                return d;
            }
            if (s.value() instanceof String str) {
                try {
                    return new BigDecimal(str.trim());
                } catch (NumberFormatException e) {
                    throw new TypeRegistry.TypeCastException("aggregate 元素非数值：" + str);
                }
            }
        }
        throw new TypeRegistry.TypeCastException("aggregate 元素非数值");
    }

    private int compare(UnifiedModel.UNode a, UnifiedModel.UNode b, boolean max) {
        Object va = ((UnifiedModel.ScalarNode) a).value();
        Object vb = ((UnifiedModel.ScalarNode) b).value();
        int cmp;
        if (va instanceof Comparable && va.getClass().equals(vb.getClass())) {
            cmp = ((Comparable) va).compareTo(vb);
        } else {
            throw new TypeRegistry.TypeCastException("aggregate 元素类型不一致，无法比较");
        }
        return max ? cmp : -cmp;
    }

    private String stringify(UnifiedModel.UNode node) {
        if (node instanceof UnifiedModel.ScalarNode s) {
            return String.valueOf(s.value());
        }
        throw new TypeRegistry.TypeCastException("CONCAT 仅支持标量元素");
    }

    // ---------- null 策略（M0-02 §3 四值） ----------

    private void applyNullStrategy(UnifiedModel out, InterfaceRow.MappingRow rule) {
        switch (rule.nullStrategy()) {
            case "KEEP" -> out.set(rule.target(), UnifiedModel.ScalarNode.nullNode());
            case "NULL" -> { /* 不写：输出省略该字段 */ }
            case "DEFAULT" -> out.set(rule.target(), zeroValue());
            case "ERROR" -> throw BizException.fieldInvalid("字段缺失：" + rule.source());
            default -> { /* 非法值已在管理面校验拦截，此处不处理 */ }
        }
    }

    /** DEFAULT 零值（M0-02 §3）：STRING → ""（类型零值表；映射不声明目标类型，默认按字符串零值） */
    private UnifiedModel.UNode zeroValue() {
        return UnifiedModel.ScalarNode.str("");
    }
}
