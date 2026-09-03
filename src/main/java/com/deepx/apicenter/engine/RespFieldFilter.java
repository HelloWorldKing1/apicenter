package com.deepx.apicenter.engine;

import com.deepx.apicenter.mapping.TypeRegistry;
import com.deepx.apicenter.model.InterfaceRow.FieldDefRow;
import com.deepx.apicenter.engine.UnifiedModel.ArrayNode;
import com.deepx.apicenter.engine.UnifiedModel.ObjectNode;
import com.deepx.apicenter.engine.UnifiedModel.ScalarNode;
import com.deepx.apicenter.engine.UnifiedModel.ScalarType;
import com.deepx.apicenter.engine.UnifiedModel.UNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RESP 出站响应字段白名单过滤 + 类型转换（D-M3-3）：
 * interface_field_def（kind=RESP）声明的 name 为白名单，未声明字段丢弃；仅成功路径生效；
 * data 为 null / 非对象时不过滤；RESP 为空 = 不过滤（既有接口不受影响）。
 *
 * <p>类型域映射约定（field_def.type 自由文本 → TypeRegistry 5 类型枚举；
 * 注意 XML 响应解码后全为 STRING，声明类型的解析转换由此矩阵覆盖）：
 * string → 实际非 STRING 时转 STRING；number → INT / DECIMAL 兼容，实际 STRING 按数值解析；
 * boolean → BOOLEAN 兼容，实际 STRING 按 "true"/"false" 解析；date → ISO 解析；
 * array / object → 结构不符保原值 + warning；转换失败一律保原值 + warning（响应方向宽松策略）；
 * 复合字段（array/object）内部透传（field_def 无嵌套声明能力）。
 */
public final class RespFieldFilter {

    private RespFieldFilter() {
    }

    /** 白名单过滤 + 类型转换；warnings 收集宽松策略的降级记录（不阻断） */
    public static UNode filter(UNode data, List<FieldDefRow> respDefs, List<String> warnings) {
        if (respDefs == null || respDefs.isEmpty()) {
            return data; // 空声明 = 不过滤
        }
        if (!(data instanceof ObjectNode obj)) {
            return data; // data 为 null / 标量 / 数组时不过滤
        }
        Map<String, FieldDefRow> byName = respDefs.stream()
                .collect(Collectors.toMap(FieldDefRow::name, Function.identity(), (a, b) -> a));
        ObjectNode out = ObjectNode.of();
        obj.fields().forEach((name, node) -> {
            FieldDefRow def = byName.get(name);
            if (def == null) {
                return; // 未声明字段丢弃
            }
            out.fields().put(name, convert(node, def, warnings));
        });
        return out;
    }

    private static UNode convert(UNode node, FieldDefRow def, List<String> warnings) {
        String declared = def.type() == null ? "string" : def.type().toLowerCase(Locale.ROOT);
        // 复合字段（array/object）内部透传；结构不符保原值 + warning
        if (node instanceof ObjectNode || node instanceof ArrayNode) {
            if (!"array".equals(declared) && !"object".equals(declared) && !"any".equals(declared)) {
                warnings.add("RESP 字段 " + def.name() + " 声明类型 " + declared + " 与实际结构不符，保留原值");
            }
            return node;
        }
        if (!(node instanceof ScalarNode s)) {
            return node;
        }
        try {
            return switch (declared) {
                case "string" -> s.type() == ScalarType.STRING
                        ? s
                        : ScalarNode.str(String.valueOf(TypeRegistry.cast(s.value(), TypeRegistry.STRING)));
                case "number" -> {
                    if (s.type() == ScalarType.INT || s.type() == ScalarType.DECIMAL) {
                        yield s; // 兼容不转换
                    }
                    // 实际 STRING（XML 响应全文本）按数值解析：先 INT 后 DECIMAL
                    try {
                        yield ScalarNode.num((Long) TypeRegistry.cast(s.value(), TypeRegistry.INT));
                    } catch (Exception e) {
                        yield ScalarNode.decimal((BigDecimal) TypeRegistry.cast(s.value(), TypeRegistry.DECIMAL));
                    }
                }
                case "boolean", "bool" -> s.type() == ScalarType.BOOLEAN
                        ? s
                        : ScalarNode.bool((Boolean) TypeRegistry.cast(s.value(), TypeRegistry.BOOL));
                case "date", "datetime" ->
                        ScalarNode.str(String.valueOf(TypeRegistry.cast(s.value(), TypeRegistry.DATE)));
                case "array", "object" -> {
                    // 标量实际 + 结构声明：结构不符保原值 + warning（复合声明仅对复合实际透传）
                    warnings.add("RESP 字段 " + def.name() + " 声明类型 " + declared + " 与实际结构不符，保留原值");
                    yield s;
                }
                default -> s; // 未知声明类型：保原值
            };
        } catch (Exception e) {
            warnings.add("RESP 字段 " + def.name() + " 按声明类型 " + declared + " 转换失败，保留原值");
            return s;
        }
    }
}
