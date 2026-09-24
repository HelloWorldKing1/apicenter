package com.deepx.apicenter.mapping;

import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.InterfaceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 映射引擎单测：M0-02 §9 的 24 例预期输出矩阵（6 操作 × 4 null_strategy）+
 * 补充断言（未命中透传 / 转换失败兜底 / 空数组 / DATE 边界 / 表达式沙箱）。
 * 纯单测，不依赖 Spring 上下文（apply(UnifiedModel, rules) 不触碰 repository）。
 */
class MappingEngineTest {

    private MappingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MappingEngine(null, new ConditionEvaluator());
    }

    /** 统一样例输入（M0-02 §9）：{"a":"1","b":null,"c":[1,2]} */
    private UnifiedModel inbound() {
        LinkedHashMap<String, UnifiedModel.UNode> fields = new LinkedHashMap<>();
        fields.put("a", UnifiedModel.ScalarNode.str("1"));
        fields.put("b", UnifiedModel.ScalarNode.nullNode());
        fields.put("c", UnifiedModel.ArrayNode.of(UnifiedModel.ScalarNode.num(1), UnifiedModel.ScalarNode.num(2)));
        return UnifiedModel.of(new UnifiedModel.ObjectNode(fields, Map.of()));
    }

    private InterfaceRow.MappingRow rule(String source, String op, String target,
                                         String param, String nullStrategy) {
        return new InterfaceRow.MappingRow(0, source, op, target, param, nullStrategy, 0);
    }

    private UnifiedModel apply(InterfaceRow.MappingRow rule) {
        return engine.apply(inbound(), List.of(rule));
    }

    /**
     * 取 target 值（缺失 → "__ABSENT__" 信号）。
     * 注意：不能用 model.get()——契约把 NULL 标量视为「不存在」，
     * 而 KEEP 策略写的就是 null 节点，需区分「写了 null」与「没写」。
     */
    private Object value(UnifiedModel out, String target) {
        if (out.root() instanceof UnifiedModel.ObjectNode obj) {
            UnifiedModel.UNode n = obj.fields().get(target);
            if (n == null) {
                return "__ABSENT__";
            }
            return n instanceof UnifiedModel.ScalarNode s ? s.value() : n;
        }
        return "__ABSENT__";
    }

    // ---------- 24 例矩阵（M0-02 §9） ----------

    @Test
    void rename_四空值策略() {
        assertThat(value(apply(rule("a", "rename", "t", null, "KEEP")), "t")).isEqualTo("1");
        assertThat(value(apply(rule("b", "rename", "t", null, "KEEP")), "t")).isNull();            // 字段存在、值为 null
        assertThat(value(apply(rule("b", "rename", "t", null, "NULL")), "t")).isEqualTo("__ABSENT__"); // 字段省略
        assertThat(value(apply(rule("b", "rename", "t", null, "DEFAULT")), "t")).isEqualTo("");     // STRING 零值
        assertThatThrownBy(() -> apply(rule("b", "rename", "t", null, "ERROR")))
                .isInstanceOf(BizException.class).hasMessageContaining("字段缺失");
    }

    @Test
    void typeCast_INT_四空值策略() {
        assertThat(value(apply(rule("a", "typeCast", "t", "INT", "KEEP")), "t")).isEqualTo(1L);
        assertThat(value(apply(rule("b", "typeCast", "t", "INT", "KEEP")), "t")).isNull();
        assertThat(value(apply(rule("b", "typeCast", "t", "INT", "NULL")), "t")).isEqualTo("__ABSENT__");
        assertThat(value(apply(rule("b", "typeCast", "t", "INT", "DEFAULT")), "t")).isEqualTo("");
        assertThatThrownBy(() -> apply(rule("b", "typeCast", "t", "INT", "ERROR")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void enumMap_四空值策略() {
        assertThat(value(apply(rule("a", "enumMap", "t", "1→0, 2→1", "KEEP")), "t")).isEqualTo("0");
        assertThat(value(apply(rule("b", "enumMap", "t", "1→0, 2→1", "KEEP")), "t")).isNull();
        assertThat(value(apply(rule("b", "enumMap", "t", "1→0, 2→1", "NULL")), "t")).isEqualTo("__ABSENT__");
        assertThat(value(apply(rule("b", "enumMap", "t", "1→0, 2→1", "DEFAULT")), "t")).isEqualTo("");
        assertThatThrownBy(() -> apply(rule("b", "enumMap", "t", "1→0, 2→1", "ERROR")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void default_常量注入四策略均无条件写入() {
        for (String ns : List.of("KEEP", "NULL", "DEFAULT", "ERROR")) {
            assertThat(value(apply(rule(null, "default", "t", "X", ns)), "t")).isEqualTo("X");
        }
    }

    @Test
    void condition_四空值策略() {
        assertThat(value(apply(rule("a", "condition", "t", "a == '1'", "KEEP")), "t")).isEqualTo("1");
        // source=b(null)：条件真但 source 为 null → 走 null 策略
        assertThat(value(apply(rule("b", "condition", "t", "b == nil", "KEEP")), "t")).isNull();
        // 条件为假 → 不写（不触发 null 策略）
        for (String ns : List.of("KEEP", "NULL", "DEFAULT", "ERROR")) {
            assertThat(value(apply(rule("a", "condition", "t", "a == '999'", ns)), "t")).isEqualTo("__ABSENT__");
        }
    }

    @Test
    void condition_source缺失且条件为假_不触发null策略不报错() {
        // 矩阵 #20 补充：source 为 null + 条件 false + ERROR → 不报错、不写
        for (String ns : List.of("KEEP", "NULL", "DEFAULT", "ERROR")) {
            assertThat(value(apply(rule("nonexistent", "condition", "t", "a == '999'", ns)), "t"))
                    .isEqualTo("__ABSENT__");
        }
        // source 为 null + 条件 true + KEEP → 写 null（走 null 策略）
        assertThat(value(apply(rule("b", "condition", "t", "a == '1'", "KEEP")), "t")).isNull();
    }

    @Test
    void aggregate_SUM_四空值策略() {
        assertThat(value(apply(rule("c", "aggregate", "t", "SUM", "KEEP")), "t")).isEqualTo(3L);
        assertThat(value(apply(rule("b", "aggregate", "t", "SUM", "KEEP")), "t")).isNull();
        assertThat(value(apply(rule("b", "aggregate", "t", "SUM", "NULL")), "t")).isEqualTo("__ABSENT__");
        assertThat(value(apply(rule("b", "aggregate", "t", "SUM", "DEFAULT")), "t")).isEqualTo("");
        assertThatThrownBy(() -> apply(rule("b", "aggregate", "t", "SUM", "ERROR")))
                .isInstanceOf(BizException.class);
    }

    // ---------- 矩阵外补充断言（M0-02 §9 补充） ----------

    @Test
    void enumMap_未命中透传() {
        assertThat(value(apply(rule("a", "enumMap", "t", "9→0", "KEEP")), "t")).isEqualTo("1");
    }

    @Test
    void typeCast_转换失败按null策略() {
        // "abc" → INT 失败：KEEP 写原值 / ERROR 整单失败
        LinkedHashMap<String, UnifiedModel.UNode> fields = new LinkedHashMap<>();
        fields.put("s", UnifiedModel.ScalarNode.str("abc"));
        UnifiedModel in = UnifiedModel.of(new UnifiedModel.ObjectNode(fields, Map.of()));
        assertThat(engine.apply(in, List.of(rule("s", "typeCast", "t", "INT", "KEEP")))
                .get("t").orElseThrow()).isEqualTo(UnifiedModel.ScalarNode.str("abc"));
        assertThatThrownBy(() -> engine.apply(in, List.of(rule("s", "typeCast", "t", "INT", "ERROR"))))
                .isInstanceOf(BizException.class);
    }

    @Test
    void aggregate_非数组与空数组() {
        assertThatThrownBy(() -> apply(rule("a", "aggregate", "t", "SUM", "ERROR")))
                .isInstanceOf(BizException.class); // 非数组 → 按 null 策略（ERROR 抛）
        LinkedHashMap<String, UnifiedModel.UNode> fields = new LinkedHashMap<>();
        fields.put("e", new UnifiedModel.ArrayNode(List.of()));
        UnifiedModel in = UnifiedModel.of(new UnifiedModel.ObjectNode(fields, Map.of()));
        UnifiedModel out = engine.apply(in, List.of(rule("e", "aggregate", "t", "SUM", "NULL")));
        assertThat(out.get("t")).isEmpty(); // 空数组视为 null → NULL 不写
    }

    @Test
    void typeCast_DATE边界_epoch秒毫秒与ISO() {
        LinkedHashMap<String, UnifiedModel.UNode> fields = new LinkedHashMap<>();
        fields.put("epochSec", UnifiedModel.ScalarNode.num(1788252017L));      // < 1e11 → 秒
        fields.put("epochMs", UnifiedModel.ScalarNode.num(1788252017000L));   // > 1e11 → 毫秒
        fields.put("iso", UnifiedModel.ScalarNode.str("2026-09-02T10:00:00"));
        UnifiedModel in = UnifiedModel.of(new UnifiedModel.ObjectNode(fields, Map.of()));
        UnifiedModel out = engine.apply(in, List.of(
                rule("epochSec", "typeCast", "t1", "DATE", "KEEP"),
                rule("epochMs", "typeCast", "t2", "DATE", "KEEP"),
                rule("iso", "typeCast", "t3", "DATE", "KEEP")));
        // 同一时刻的秒/毫秒 epoch 输出应一致（UTC 输出）；ISO 输入原样输出
        assertThat((String) value(out, "t1")).isEqualTo((String) value(out, "t2"));
        assertThat((String) value(out, "t1")).startsWith("2026-09-01");
        assertThat((String) value(out, "t3")).isEqualTo("2026-09-02T10:00:00");
    }

    @Test
    void condition_沙箱拦截与超长拒绝() {
        // 未定义变量 → 求值异常（50000），注入用例（M0-02 §5）
        assertThatThrownBy(() -> apply(rule("a", "condition", "t", "a + unknown_var_xyz > 0", "KEEP")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("求值异常");
        // 表达式超长（> 500，与 param 列宽对齐）→ 拒绝
        assertThatThrownBy(() -> apply(rule("a", "condition", "t", "a".repeat(600), "KEEP")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超限");
    }

    @Test
    void condition_点路径下划线别名() {
        LinkedHashMap<String, UnifiedModel.UNode> inner = new LinkedHashMap<>();
        inner.put("seller_id", UnifiedModel.ScalarNode.str("7494312521977267257"));
        LinkedHashMap<String, UnifiedModel.UNode> fields = new LinkedHashMap<>();
        fields.put("filter", new UnifiedModel.ObjectNode(inner, Map.of()));
        fields.put("a", UnifiedModel.ScalarNode.str("1"));
        UnifiedModel in = UnifiedModel.of(new UnifiedModel.ObjectNode(fields, Map.of()));
        UnifiedModel out = engine.apply(in, List.of(rule("a", "condition", "t",
                "filter_seller_id == '7494312521977267257'", "KEEP")));
        assertThat(value(out, "t")).isEqualTo("1"); // 别名变量可解析 → 条件真 → 搬移 a
    }

    // ---------- 层级嵌套（2026-09-24 按使用方提问补充的回归） ----------

    /** 扁平 → 多层嵌套：`a.id` ⇒ `b.data.id`（target 自动创建中间 OBJECT） */
    @Test
    void 嵌套_扁平到多层_object自动建中间节点() {
        LinkedHashMap<String, UnifiedModel.UNode> f = new LinkedHashMap<>();
        LinkedHashMap<String, UnifiedModel.UNode> a = new LinkedHashMap<>();
        a.put("id", UnifiedModel.ScalarNode.num(123));
        f.put("a", new UnifiedModel.ObjectNode(a, Map.of()));
        UnifiedModel in = UnifiedModel.of(new UnifiedModel.ObjectNode(f, Map.of()));

        UnifiedModel out = engine.apply(in, List.of(rule("a.id", "rename", "b.data.id", null, "NULL")));

        // {"b":{"data":{"id":123}}} —— 逐层断言（不看 JSON 字符串，避免序列化干扰）
        UnifiedModel.ObjectNode b = (UnifiedModel.ObjectNode) ((UnifiedModel.ObjectNode) out.root()).fields().get("b");
        UnifiedModel.ObjectNode data = (UnifiedModel.ObjectNode) b.fields().get("data");
        assertThat(((UnifiedModel.ScalarNode) data.fields().get("id")).value()).isEqualTo(123L);
    }

    /** 嵌套 → 扁平：`a.b.c` ⇒ `flat`；**深层 → 更深**：`x.y` ⇒ `x.y.z`（同名对象原地追加） */
    @Test
    void 嵌套_嵌套到扁平_以及深层到更深() {
        LinkedHashMap<String, UnifiedModel.UNode> c = new LinkedHashMap<>();
        c.put("c", UnifiedModel.ScalarNode.str("deep"));
        LinkedHashMap<String, UnifiedModel.UNode> b = new LinkedHashMap<>();
        b.put("b", new UnifiedModel.ObjectNode(c, Map.of()));
        LinkedHashMap<String, UnifiedModel.UNode> x = new LinkedHashMap<>();
        x.put("y", UnifiedModel.ScalarNode.str("v"));
        LinkedHashMap<String, UnifiedModel.UNode> f = new LinkedHashMap<>();
        f.put("a", new UnifiedModel.ObjectNode(b, Map.of()));
        f.put("x", new UnifiedModel.ObjectNode(x, Map.of()));
        UnifiedModel in = UnifiedModel.of(new UnifiedModel.ObjectNode(f, Map.of()));

        UnifiedModel out = engine.apply(in, List.of(
                rule("a.b.c", "rename", "flat", null, "NULL"),
                rule("x.y", "rename", "x.y.z", null, "NULL")));

        UnifiedModel.ObjectNode root = (UnifiedModel.ObjectNode) out.root();
        assertThat(((UnifiedModel.ScalarNode) root.fields().get("flat")).value()).isEqualTo("deep");
        UnifiedModel.ObjectNode xOut = (UnifiedModel.ObjectNode) root.fields().get("x");
        UnifiedModel.ObjectNode yOut = (UnifiedModel.ObjectNode) xOut.fields().get("y");
        assertThat(((UnifiedModel.ScalarNode) yOut.fields().get("z")).value()).isEqualTo("v");
    }

    /**
     * **文档化限制（M0-02 D1）**：路径**不支持数组下标寻址** —— `list[0].id` 会被当成
     * 字面键 `list[0]` 去查（必然取不到）⇒ 按 nullStrategy 处理，而**不是**报错或猜下标。
     * 数组元素级转换请用 `aggregate`（SUM/MAX/MIN/CONCAT），或改由协议适配器的同名合并 + 前端拆字段。
     */
    @Test
    void 嵌套_数组下标不被支持_按null策略处理而非报错() {
        LinkedHashMap<String, UnifiedModel.UNode> f = new LinkedHashMap<>();
        LinkedHashMap<String, UnifiedModel.UNode> item0 = new LinkedHashMap<>();
        item0.put("id", UnifiedModel.ScalarNode.num(7));
        f.put("list", UnifiedModel.ArrayNode.of(new UnifiedModel.ObjectNode(item0, Map.of())));
        f.put("nums", UnifiedModel.ArrayNode.of(UnifiedModel.ScalarNode.num(1), UnifiedModel.ScalarNode.num(2)));
        UnifiedModel in = UnifiedModel.of(new UnifiedModel.ObjectNode(f, Map.of()));

        // NULL 策略：不写该字段（既不报错，也拿不到值）
        UnifiedModel outNull = engine.apply(in, List.of(rule("list[0].id", "rename", "got", null, "NULL")));
        assertThat(((UnifiedModel.ObjectNode) outNull.root()).fields()).doesNotContainKey("got");

        // KEEP 策略：写 null 节点（仍然是"没取到"，只是形态不同）
        UnifiedModel outKeep = engine.apply(in, List.of(rule("list[0].id", "rename", "got", null, "KEEP")));
        assertThat(value(outKeep, "got")).isNull();

        // 对比：**整数组聚合是支持的**（元素级请用它）—— 数值数组求和
        UnifiedModel outAgg = engine.apply(in, List.of(
                rule("nums", "aggregate", "sum", "SUM", "NULL")));
        assertThat(value(outAgg, "sum")).isEqualTo(3L);
    }

    @Test
    void 空规则整体透传_非空白名单() {
        UnifiedModel in = inbound();
        assertThat(engine.apply(in, List.of())).isSameAs(in); // 空规则 = 原对象透传（D3）
        UnifiedModel out = engine.apply(inbound(), List.of(rule("a", "rename", "a", null, "KEEP")));
        assertThat(out.get("a")).isPresent();
        assertThat(out.get("c")).isEmpty(); // 非空映射 = 白名单：未声明字段不进出入站报文
    }
}
