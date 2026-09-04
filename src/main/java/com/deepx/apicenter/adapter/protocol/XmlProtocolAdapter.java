package com.deepx.apicenter.adapter.protocol;

import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.AdapterType;
import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.engine.UnifiedModel.ArrayNode;
import com.deepx.apicenter.engine.UnifiedModel.ObjectNode;
import com.deepx.apicenter.engine.UnifiedModel.ScalarNode;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.mapping.TypeRegistry;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * XML 协议编解码（M3 交付，D-M3-1 语义；M0-01 D5 首期平台默认参数）：
 *
 * <p>DECODE（原始字节 ctx.attrs("rawBody") → UnifiedModel）：
 * <ul>
 *   <li>元素 → OBJECT fields（多次同名合并为 ARRAY、单次保持单值）；空元素 / 纯空白内容 → NULL（与编码对称）；</li>
 *   <li>XML 属性 → attributes（JSON 输出时属性丢弃，仅 XML→XML 场景保留）；CDATA 按文本；</li>
 *   <li>混合内容（文本与子元素并存）→ 40002 拒绝；命名空间首期忽略（取 localName 剥离前缀）；根元素名不进入模型；</li>
 *   <li>DTD / 实体声明 → 40002 拒绝（XXE 防护，平台默认参数无合法 DTD 场景）；</li>
 *   <li>顶层字段按接口入站参数声明类型（ctx.attrs("paramTypes")，name→type）经 TypeRegistry 转换，
 *       转换失败 / 未声明参数保持 STRING。</li>
 * </ul>
 *
 * <p>ENCODE（UnifiedModel → XML 字节，写 ctx.outbound.body + Content-Type application/xml）：
 * fields → 元素、attributes → 属性；NULL 写空元素；根元素按方向约定
 * （ctx.attrs("xmlRoot")，默认 request——ACK 回执渲染时传 response）。
 *
 * <p>实现说明：直接用 Woodstox StAX（woodstox-core/stax2-api，jackson-dataformat-xml 的传递依赖）
 * 而非 XmlMapper 树模型——readTree 无法区分 XML 属性与子元素，事件流可精确实现 D-M3-1 语义。
 * StAX 工厂线程安全（仅创建 reader/writer，每次解析新建实例）。
 */
@Component("XmlProtocolAdapter")
public class XmlProtocolAdapter implements Adapter {

    /** 编解码往返一致的约定根元素（D-M3-1：请求 request / 响应 response，按方向） */
    public static final String ROOT_REQUEST = "request";
    public static final String ROOT_RESPONSE = "response";

    /** 解码递归深度上限（评审建议 7：防万层嵌套栈溢出） */
    private static final int MAX_DEPTH = 512;

    private final XMLInputFactory inputFactory;
    private final XMLOutputFactory outputFactory;

    public XmlProtocolAdapter() {
        this.inputFactory = XMLInputFactory.newFactory();
        // XXE 双保险（探针拒绝为第一道，工厂级禁用为第二道）：
        // 支持 DTD=false、外部实体=false；实现不支持该属性时忽略（探针兜底）
        try {
            this.inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            this.inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        } catch (IllegalArgumentException ignored) {
        }
        this.outputFactory = XMLOutputFactory.newFactory();
    }

    @Override
    public AdapterType type() {
        return AdapterType.PROTOCOL;
    }

    @Override
    public AdapterContext process(AdapterContext ctx) {
        return switch (ctx.phase()) {
            case DECODE -> decode(ctx);
            case ENCODE -> encode(ctx);
            default -> ctx;
        };
    }

    // ---------- DECODE ----------

    private AdapterContext decode(AdapterContext ctx) {
        byte[] raw = (byte[]) ctx.attrs().get("rawBody");
        if (raw == null || raw.length == 0) {
            ctx.payload().root(ObjectNode.of());
            return ctx;
        }
        try {
            rejectDtd(raw);
            XMLStreamReader reader = inputFactory.createXMLStreamReader(new ByteArrayInputStream(raw));
            // 前进到根元素（StAX 初始状态为 START_DOCUMENT，此时 getLocalName 不可用；
            // 跳过注释 / 处理指令，声明头由解析器自动消费）
            int ev;
            do {
                if (!reader.hasNext()) {
                    throw new BizException(40002, "报文格式非法：缺少根元素");
                }
                ev = reader.next();
            } while (ev == XMLStreamConstants.COMMENT || ev == XMLStreamConstants.PROCESSING_INSTRUCTION);
            if (ev != XMLStreamConstants.START_ELEMENT) {
                throw new BizException(40002, "报文格式非法：缺少根元素");
            }
            UnifiedModel.UNode root = readElement(reader, 0);
            ctx.payload().root(root);
            applyParamTypes(ctx);
            return ctx;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(40002, "报文格式非法：" + e.getMessage());
        }
    }

    /** XXE 防护第一道：报文含 DTD / 实体声明一律 40002（合法 XML 中 &lt;!DOCTYPE 必为连续 token，全量探针可靠） */
    private void rejectDtd(byte[] raw) {
        String probe = new String(raw, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (probe.contains("<!doctype") || probe.contains("<!entity")) {
            throw new BizException(40002, "报文格式非法：XML 不允许 DTD / 实体声明");
        }
    }

    /** 递归读元素：容器元素 → ObjectNode（fields + attributes），叶元素 → 标量（文本 STRING / 空 NULL） */
    private UnifiedModel.UNode readElement(XMLStreamReader r, int depth) throws XMLStreamException {
        // 深度上限（评审建议 7）：1MB 限制是间接兜底，万层嵌套可栈溢出，显式 40002
        if (depth > MAX_DEPTH) {
            throw new BizException(40002, "报文格式非法：XML 嵌套深度超过 " + MAX_DEPTH);
        }
        String name = r.getLocalName();
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i < r.getAttributeCount(); i++) {
            attributes.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
        }
        LinkedHashMap<String, UnifiedModel.UNode> fields = new LinkedHashMap<>();
        boolean hasChildren = false;
        StringBuilder text = new StringBuilder();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                hasChildren = true;
                String childName = r.getLocalName();
                merge(fields, childName, readElement(r, depth + 1)); // 递归消费到子元素 END_ELEMENT
            } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                text.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                break; // 本元素结束
            }
        }
        if (hasChildren) {
            if (!text.toString().isBlank()) {
                throw new BizException(40002, "报文格式非法：混合内容不支持（元素 " + name + "）");
            }
            return new ObjectNode(fields, attributes);
        }
        // 叶元素：纯空白 / 无内容 → NULL；否则 STRING（CDATA 内容原样保留）
        String t = text.toString();
        return t.isBlank() ? ScalarNode.nullNode() : ScalarNode.str(t);
    }

    /** 同名元素合并（D-M3-1）：单次保持单值，多次合并为 ARRAY */
    private void merge(LinkedHashMap<String, UnifiedModel.UNode> fields, String name, UnifiedModel.UNode child) {
        UnifiedModel.UNode existing = fields.get(name);
        if (existing == null) {
            fields.put(name, child);
        } else if (existing instanceof ArrayNode arr) {
            arr.items().add(child);
        } else {
            fields.put(name, ArrayNode.of(existing, child));
        }
    }

    /** 顶层字段按入站参数声明类型转换（D-M3-1：XML 全文本；未声明 / 转换失败保持 STRING） */
    @SuppressWarnings("unchecked")
    private void applyParamTypes(AdapterContext ctx) {
        Object types = ctx.attrs().get("paramTypes");
        if (!(types instanceof Map<?, ?> map) || !(ctx.payload().root() instanceof ObjectNode obj)) {
            return;
        }
        for (Map.Entry<String, UnifiedModel.UNode> e : obj.fields().entrySet()) {
            if (!(e.getValue() instanceof ScalarNode s)) {
                continue; // 结构类型（array/object 声明）不转换
            }
            Object declared = map.get(e.getKey());
            if (declared == null) {
                continue;
            }
            ScalarNode converted = convert(declared.toString(), s);
            if (converted == null) {
                ctx.warn("XML 字段 " + e.getKey() + " 按声明类型 " + declared + " 转换失败，保留 STRING");
            } else {
                obj.fields().put(e.getKey(), converted);
            }
        }
    }

    /** param 声明类型 → 标量节点；未知类型 / 转换失败返回 null（调用方保留原值） */
    private ScalarNode convert(String declared, ScalarNode s) {
        if (s.type() != UnifiedModel.ScalarType.STRING) {
            return null; // 仅对全文本做提示转换
        }
        String lower = declared.toLowerCase(Locale.ROOT);
        try {
            return switch (lower) {
                case "number" -> {
                    try {
                        yield ScalarNode.num((Long) TypeRegistry.cast(s.value(), TypeRegistry.INT));
                    } catch (Exception e) {
                        yield ScalarNode.decimal((BigDecimal) TypeRegistry.cast(s.value(), TypeRegistry.DECIMAL));
                    }
                }
                case "string" -> s; // 已是 STRING
                case "boolean", "bool" -> ScalarNode.bool((Boolean) TypeRegistry.cast(s.value(), TypeRegistry.BOOL));
                case "date", "datetime" ->
                        ScalarNode.str(String.valueOf(TypeRegistry.cast(s.value(), TypeRegistry.DATE)));
                default -> null; // array/object 等结构声明或未知类型不转换
            };
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- ENCODE ----------

    private AdapterContext encode(AdapterContext ctx) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLStreamWriter w = outputFactory.createXMLStreamWriter(out, "UTF-8");
            w.writeStartDocument("UTF-8", "1.0");
            String root = ctx.attrs().get("xmlRoot") instanceof String s ? s : ROOT_REQUEST;
            if (!(ctx.payload().root() instanceof ObjectNode)) {
                // XML 文档单根约束（评审 N3）：数组/标量根无合法 XML 表示，明确报错而非产出非法多根文档
                throw new BizException(50000, "报文编码失败：XML 根节点必须为对象");
            }
            writeNode(w, root, ctx.payload().root());
            w.writeEndDocument();
            w.flush();
            w.close();
            ctx.outbound().body(out.toByteArray());
            ctx.outbound().header("Content-Type", "application/xml");
            ctx.outbound().header("Accept", "application/xml");
            return ctx;
        } catch (Exception e) {
            throw new BizException(50000, "报文编码失败：" + e.getMessage());
        }
    }

    /** 递归写出：fields → 元素、attributes → 属性；NULL 写空元素（D5 空值包含，与解码对称） */
    private void writeNode(XMLStreamWriter w, String name, UnifiedModel.UNode node) throws XMLStreamException {
        switch (node) {
            case ObjectNode obj -> {
                w.writeStartElement(name);
                writeAttributes(w, obj.attributes());
                obj.fields().forEach((k, v) -> writeNodeQuiet(w, k, v));
                w.writeEndElement();
            }
            case ArrayNode arr -> arr.items().forEach(v -> writeNodeQuiet(w, name, v));
            case ScalarNode s -> {
                if (s.type() == UnifiedModel.ScalarType.NULL) {
                    w.writeEmptyElement(name);
                } else {
                    w.writeStartElement(name);
                    w.writeCharacters(scalarText(s));
                    w.writeEndElement();
                }
            }
        }
    }

    private void writeAttributes(XMLStreamWriter w, Map<String, String> attributes) {
        attributes.forEach((k, v) -> {
            try {
                w.writeAttribute(k, v);
            } catch (XMLStreamException e) {
                throw new XmlWriteRuntimeException(e);
            }
        });
    }

    private void writeNodeQuiet(XMLStreamWriter w, String name, UnifiedModel.UNode node) {
        try {
            writeNode(w, name, node);
        } catch (XMLStreamException e) {
            throw new XmlWriteRuntimeException(e);
        }
    }

    private String scalarText(ScalarNode s) {
        return switch (s.type()) {
            case STRING -> (String) s.value();
            case INT -> String.valueOf(s.value());
            case DECIMAL -> ((BigDecimal) s.value()).toPlainString();
            case BOOLEAN -> String.valueOf(s.value());
            case NULL -> "";
        };
    }

    /** StAX 写异常包装（writeAttributes 的 lambda 内不便抛受检异常） */
    private static final class XmlWriteRuntimeException extends RuntimeException {
        XmlWriteRuntimeException(XMLStreamException cause) {
            super(cause);
        }
    }
}
