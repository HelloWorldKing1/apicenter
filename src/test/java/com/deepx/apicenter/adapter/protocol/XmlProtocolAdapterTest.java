package com.deepx.apicenter.adapter.protocol;

import com.deepx.apicenter.client.OutboundRequestSpec;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.ChainPhase;
import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.engine.UnifiedModel.ArrayNode;
import com.deepx.apicenter.engine.UnifiedModel.ObjectNode;
import com.deepx.apicenter.engine.UnifiedModel.ScalarNode;
import com.deepx.apicenter.engine.UnifiedModel.ScalarType;
import com.deepx.apicenter.exception.BizException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * XmlProtocolAdapter 单测矩阵（M3 开发计划 §4，D-M3-1 边界）：
 * 同名合并 / 空元素 ↔ NULL 往返 / 属性 / CDATA / 混合内容 40002 / DTD·外部实体拒绝（XXE）/
 * 命名空间剥离 / 根元素约定 / paramTypes 类型转换 / 编解码往返。
 */
class XmlProtocolAdapterTest {

    private static final AdapterContext.InterfaceMeta META = new AdapterContext.InterfaceMeta(
            1L, "T-XML", "OUTBOUND", "POST", "/t/xml", "XML", "XML", "/up", null, 3000, 4);

    private final XmlProtocolAdapter adapter = new XmlProtocolAdapter();

    // ---------- 解码 ----------

    @Test
    void decodeBasicStructureAndSameNameMerge() {
        String xml = """
                <response><code>0</code><data><total>2</total>
                <items><item><id>1</id></item><item><id>2</id></item></items></data></response>""";
        ObjectNode root = decodeRoot(bytes(xml));

        assertThat(((ScalarNode) root.fields().get("code")).value()).isEqualTo("0");
        ObjectNode data = (ObjectNode) root.fields().get("data");
        assertThat(((ScalarNode) data.fields().get("total")).value()).isEqualTo("2");
        // 同名合并：items 单次出现 → ObjectNode；内部 item 同名两次 → 合并 ARRAY；item 内 id 单次 → 单值
        ObjectNode items = (ObjectNode) data.fields().get("items");
        ArrayNode itemList = (ArrayNode) items.fields().get("item");
        assertThat(itemList.items()).hasSize(2);
        ObjectNode first = (ObjectNode) itemList.items().get(0);
        assertThat(((ScalarNode) first.fields().get("id")).value()).isEqualTo("1");
    }

    @Test
    void decodeParamTypeHints() {
        String xml = "<request><count>42</count><ratio>1.5</ratio><ok>true</ok><name>abc</name></request>";
        ObjectNode root = decodeRoot(bytes(xml), Map.of(
                "count", "number", "ratio", "number", "ok", "boolean", "name", "string"));

        assertThat(((ScalarNode) root.fields().get("count")).type()).isEqualTo(ScalarType.INT);
        assertThat(((ScalarNode) root.fields().get("count")).value()).isEqualTo(42L);
        assertThat(((ScalarNode) root.fields().get("ratio")).type()).isEqualTo(ScalarType.DECIMAL);
        assertThat(((ScalarNode) root.fields().get("ok")).type()).isEqualTo(ScalarType.BOOLEAN);
        assertThat(((ScalarNode) root.fields().get("name")).type()).isEqualTo(ScalarType.STRING);
    }

    @Test
    void decodeParamTypeConversionFailureKeepsStringWithWarning() {
        AdapterContext ctx = decodeCtx(bytes("<request><count>abc</count></request>"), Map.of("count", "number"));
        adapter.process(ctx);

        ObjectNode root = (ObjectNode) ctx.payload().root();
        assertThat(((ScalarNode) root.fields().get("count")).type()).isEqualTo(ScalarType.STRING);
        assertThat(ctx.warnings()).anyMatch(w -> w.contains("count"));
    }

    @Test
    void decodeEmptyAndBlankElementsAreNull() {
        ObjectNode root = decodeRoot(bytes("<request><a></a><b>   </b><c>x</c></request>"));

        assertThat(((ScalarNode) root.fields().get("a")).type()).isEqualTo(ScalarType.NULL);
        assertThat(((ScalarNode) root.fields().get("b")).type()).isEqualTo(ScalarType.NULL);
        assertThat(((ScalarNode) root.fields().get("c")).value()).isEqualTo("x");
    }

    @Test
    void decodeAttributesAndCdata() {
        ObjectNode root = decodeRoot(bytes("<request mode=\"fast\"><a><![CDATA[<raw>]]></a></request>"));

        assertThat(root.attributes()).containsEntry("mode", "fast");
        assertThat(((ScalarNode) root.fields().get("a")).value()).isEqualTo("<raw>");
    }

    @Test
    void decodeNamespacePrefixStrippedAndRootIgnored() {
        ObjectNode root = decodeRoot(bytes(
                "<ns1:anything xmlns:ns1=\"http://example.com/x\"><ns1:a>1</ns1:a></ns1:anything>"));

        assertThat(root.fields()).containsKey("a"); // localName 剥离前缀；根元素名 anything 不进入模型
        assertThat(((ScalarNode) root.fields().get("a")).value()).isEqualTo("1");
    }

    @Test
    void decodeMixedContentRejected() {
        assertThatThrownBy(() -> decodeRoot(bytes("<request><a>text<b>1</b></a></request>")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40002));
    }

    @Test
    void decodeDtdAndEntityRejectedXxe() {
        assertThatThrownBy(() -> decodeRoot(bytes(
                "<?xml version=\"1.0\"?><!DOCTYPE a SYSTEM \"file:///etc/passwd\"><a>x</a>")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40002));
        assertThatThrownBy(() -> decodeRoot(bytes(
                "<!DOCTYPE a [<!ENTITY xxe \"y\">]><a>&xxe;</a>")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40002));
    }

    @Test
    void decodeMalformedXmlRejected() {
        assertThatThrownBy(() -> decodeRoot(bytes("not xml <<<")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(40002));
    }

    @Test
    void decodeEmptyBodyIsEmptyObject() {
        ObjectNode root = decodeRoot(new byte[0]);
        assertThat(root.fields()).isEmpty();
    }

    // ---------- 编码 ----------

    @Test
    void encodeDefaultRootAndHeaders() {
        AdapterContext ctx = encodeCtx(model(ObjectNode.of()), null);
        adapter.process(ctx);

        String out = new String(ctx.outbound().body(), StandardCharsets.UTF_8);
        // Woodstox 声明用单引号且无结尾空格；空对象根元素自闭合 <request/>
        assertThat(out).startsWith("<?xml");
        assertThat(out).contains("version=");
        assertThat(out).contains("<request");
        assertThat(ctx.outbound().headers().get("Content-Type")).containsExactly("application/xml");
        assertThat(ctx.outbound().headers().get("Accept")).containsExactly("application/xml");
    }

    @Test
    void encodeRootElementByDirection() {
        AdapterContext ctx = encodeCtx(model(ObjectNode.of()), "response");
        adapter.process(ctx);

        assertThat(new String(ctx.outbound().body(), StandardCharsets.UTF_8)).contains("<response");
    }

    // ---------- 往返 ----------

    @Test
    void roundTripPreservesNullAttributesAndArray() {
        LinkedHashMap<String, UnifiedModel.UNode> fields = new LinkedHashMap<>();
        fields.put("name", ScalarNode.str("x"));
        fields.put("empty", ScalarNode.nullNode());
        fields.put("nums", ArrayNode.of(ScalarNode.str("1"), ScalarNode.str("2")));
        ObjectNode source = new ObjectNode(fields, Map.of("mode", "fast"));

        AdapterContext encCtx = encodeCtx(UnifiedModel.of(source), null);
        adapter.process(encCtx);
        AdapterContext decCtx = decodeCtx(encCtx.outbound().body(), Map.of());
        adapter.process(decCtx);

        ObjectNode back = (ObjectNode) decCtx.payload().root();
        assertThat(((ScalarNode) back.fields().get("name")).value()).isEqualTo("x");
        assertThat(((ScalarNode) back.fields().get("empty")).type()).isEqualTo(ScalarType.NULL); // 空元素 ↔ NULL
        assertThat(((ArrayNode) back.fields().get("nums")).items()).hasSize(2);
        assertThat(back.attributes()).containsEntry("mode", "fast"); // XML→XML 属性保留
    }

    // ---------- 辅助 ----------

    private ObjectNode decodeRoot(byte[] raw) {
        return decodeRoot(raw, Map.of());
    }

    private ObjectNode decodeRoot(byte[] raw, Map<String, String> paramTypes) {
        AdapterContext ctx = decodeCtx(raw, paramTypes);
        adapter.process(ctx);
        return (ObjectNode) ctx.payload().root();
    }

    private AdapterContext decodeCtx(byte[] raw, Map<String, String> paramTypes) {
        AdapterContext ctx = baseCtx(ChainPhase.DECODE, UnifiedModel.emptyObject());
        ctx.attrs().put("rawBody", raw);
        if (!paramTypes.isEmpty()) {
            ctx.attrs().put("paramTypes", paramTypes);
        }
        return ctx;
    }

    private AdapterContext encodeCtx(UnifiedModel model, String xmlRoot) {
        AdapterContext ctx = baseCtx(ChainPhase.ENCODE, model);
        if (xmlRoot != null) {
            ctx.attrs().put("xmlRoot", xmlRoot);
        }
        return ctx;
    }

    private AdapterContext baseCtx(ChainPhase phase, UnifiedModel model) {
        return AdapterContext.create(phase, model, META,
                new AdapterContext.AppMeta("t-app", "http://mock"),
                new AdapterContext.TraceMeta("t1"), null, new OutboundRequestSpec());
    }

    private UnifiedModel model(UnifiedModel.UNode root) {
        return UnifiedModel.of(root);
    }

    private byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
