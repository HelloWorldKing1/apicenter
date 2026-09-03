package com.deepx.apicenter.engine;

import com.deepx.apicenter.engine.UnifiedModel.ArrayNode;
import com.deepx.apicenter.engine.UnifiedModel.ObjectNode;
import com.deepx.apicenter.engine.UnifiedModel.ScalarNode;
import com.deepx.apicenter.engine.UnifiedModel.ScalarType;
import com.deepx.apicenter.model.InterfaceRow.FieldDefRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RESP 白名单过滤 + 类型转换单测矩阵（D-M3-3）：
 * 未声明丢弃 / 类型转换（含 XML 全 STRING 解析）/ 转换失败宽松保原值 / 复合透传 /
 * 空声明不过滤 / data 非对象不过滤。
 */
class RespFieldFilterTest {

    private static FieldDefRow def(String name, String type) {
        return new FieldDefRow(0, "RESP", name, type, null, 0);
    }

    private ObjectNode data() {
        ObjectNode node = ObjectNode.of();
        node.fields().put("total", ScalarNode.num(822L));          // INT
        node.fields().put("ratio", ScalarNode.decimal(new java.math.BigDecimal("1.5")));
        node.fields().put("nickname", ScalarNode.str("megan!"));
        node.fields().put("ok", ScalarNode.bool(true));
        node.fields().put("list", ArrayNode.of(ScalarNode.str("a"))); // 复合字段
        node.fields().put("extra", ScalarNode.str("未声明"));
        return node;
    }

    @Test
    void 未声明字段丢弃() {
        ObjectNode out = (ObjectNode) RespFieldFilter.filter(data(),
                List.of(def("total", "number"), def("list", "array")), new ArrayList<>());
        assertThat(out.fields()).containsOnlyKeys("total", "list"); // extra/nickname/ok/ratio 被过滤
    }

    @Test
    void 类型转换number兼容int与decimal() {
        ObjectNode out = (ObjectNode) RespFieldFilter.filter(data(),
                List.of(def("total", "number"), def("ratio", "number")), new ArrayList<>());
        assertThat(((ScalarNode) out.fields().get("total")).type()).isEqualTo(ScalarType.INT); // 兼容不转换
        assertThat(((ScalarNode) out.fields().get("ratio")).type()).isEqualTo(ScalarType.DECIMAL);
    }

    @Test
    void xml全文本按声明类型解析() {
        // XML 响应解码后全为 STRING：number/boolean 从文本解析
        ObjectNode node = ObjectNode.of();
        node.fields().put("total", ScalarNode.str("2"));
        node.fields().put("ok", ScalarNode.str("true"));
        ObjectNode out = (ObjectNode) RespFieldFilter.filter(node,
                List.of(def("total", "number"), def("ok", "boolean")), new ArrayList<>());
        assertThat(((ScalarNode) out.fields().get("total")).value()).isEqualTo(2L);
        assertThat(((ScalarNode) out.fields().get("ok")).value()).isEqualTo(true);
    }

    @Test
    void 转换失败保原值并告警() {
        ObjectNode node = ObjectNode.of();
        node.fields().put("total", ScalarNode.str("abc"));
        List<String> warnings = new ArrayList<>();
        ObjectNode out = (ObjectNode) RespFieldFilter.filter(node, List.of(def("total", "number")), warnings);
        assertThat(((ScalarNode) out.fields().get("total")).type()).isEqualTo(ScalarType.STRING);
        assertThat(warnings).anyMatch(w -> w.contains("total"));
    }

    @Test
    void 复合字段透传与结构不符保原值() {
        List<String> warnings = new ArrayList<>();
        ObjectNode out = (ObjectNode) RespFieldFilter.filter(data(),
                List.of(def("list", "array"), def("nickname", "array")), warnings);
        assertThat(out.fields().get("list")).isInstanceOf(ArrayNode.class); // 声明 array → 透传
        assertThat(((ScalarNode) out.fields().get("nickname")).type()).isEqualTo(ScalarType.STRING); // 结构不符保原值
        assertThat(warnings).anyMatch(w -> w.contains("nickname"));
    }

    @Test
    void 空声明不过滤() {
        ObjectNode data = data();
        assertThat(RespFieldFilter.filter(data, List.of(), new ArrayList<>())).isSameAs(data);
        assertThat(RespFieldFilter.filter(data, null, new ArrayList<>())).isSameAs(data);
    }

    @Test
    void data非对象不过滤() {
        ScalarNode scalar = ScalarNode.str("x");
        assertThat(RespFieldFilter.filter(scalar, List.of(def("a", "string")), new ArrayList<>())).isSameAs(scalar);
        assertThat(RespFieldFilter.filter(null, List.of(def("a", "string")), new ArrayList<>())).isNull();
    }

    @Test
    void string声明转换非字符串标量() {
        ObjectNode node = ObjectNode.of();
        node.fields().put("code", ScalarNode.num(0L));
        ObjectNode out = (ObjectNode) RespFieldFilter.filter(node, List.of(def("code", "string")), new ArrayList<>());
        assertThat(((ScalarNode) out.fields().get("code")).type()).isEqualTo(ScalarType.STRING);
    }
}
