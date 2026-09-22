package com.deepx.apicenter.adapter.protocol;

import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.AdapterType;
import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.engine.UnifiedModel.ArrayNode;
import com.deepx.apicenter.engine.UnifiedModel.ObjectNode;
import com.deepx.apicenter.engine.UnifiedModel.ScalarNode;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.engine.XmlProtoConfig;
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

    /**
     * ack 模式标记（B2）：仅 {@code AckRenderer} 置位。
     * <p>为何需要它：ack 与出站请求都走本适配器的 ENCODE，但 <b>ack 不得被 SOAP 包裹</b>（D-SOAP-6：
     * ack 是平台回给回调方的回执，供应商回调普遍是普通 POST）。仅凭 `xmlRoot` 存在判断不够显式，
     * 故用独立属性把它写成“显式意图”。
     */
    public static final String ATTR_ACK_MODE = "xmlAckMode";

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
        XMLStreamReader reader = null;
        try {
            rejectDtd(raw);
            reader = inputFactory.createXMLStreamReader(new ByteArrayInputStream(raw));
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
            // B2：SOAP 响应解包（必须在类型转换之前）。
            // 注：入站请求方向不注入协议参数（Q17），所以只有「响应解码」会走到解包。
            unwrapSoapIfConfigured(ctx);
            applyParamTypes(ctx);
            return ctx;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(40002, "报文格式非法：" + e.getMessage());
        } finally {
            // 2026-09-18 修复（评审 P3）：XMLStreamReader 未关闭（StAX 规范要求显式 close）
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响已解析结果
                }
            }
        }
    }

    /** XXE 防护第一道：报文含 DTD / 实体声明一律 40002（合法 XML 中 &lt;!DOCTYPE 必为连续 token，全量探针可靠） */
    private void rejectDtd(byte[] raw) {
        String probe = new String(raw, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (probe.contains("<!doctype") || probe.contains("<!entity")) {
            throw new BizException(40002, "报文格式非法：XML 不允许 DTD / 实体声明");
        }
    }

    /**
     * SOAP 响应解包（B2）：取 {@code Envelope/Body} 内**第一个子元素**作为业务根；
     * 宽容失败（非 SOAP 结构 / Body 为空 → 原样返回 + warn），避免把非 SOAP 报文判死。
     *
     * <p>注意：解码后根元素名**不进模型**（D-M3-1），所以模型根已是 `Envelope` 的**内容**（即 `{Body:{…}}`），
     * 此处直接取 `Body` 字段即可，无需再找 `Envelope`。
     */
    private void unwrapSoapIfConfigured(AdapterContext ctx) {
        XmlProtoConfig cfg = ctx.attrs().get(XmlProtoConfig.ATTR) instanceof XmlProtoConfig c ? c : null;
        if (cfg == null || !cfg.isSoap() || !cfg.unwrapResponse()) {
            return;
        }
        if (!(ctx.payload().root() instanceof ObjectNode env)) {
            return; // 非对象根（空报文 / 标量）：原样
        }
        UnifiedModel.UNode bodyNode = env.fields().get("Body");
        if (!(bodyNode instanceof ObjectNode body) || body.fields().isEmpty()) {
            ctx.warn("SOAP 解包：响应缺少 Envelope/Body 结构，按原样处理（请核对供应商是否真为 SOAP）");
            return;
        }
        var it = body.fields().entrySet().iterator();
        var first = it.next();
        if (it.hasNext()) {
            ctx.warn("SOAP 解包：Body 内有多个子元素，只取第一个：" + first.getKey());
        }
        ctx.payload().root(first.getValue());
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
            // 协议参数（B1/B2）：由 ChainEngine 装配期烘焙后注入；缺省 = 内置默认（= 改造前行为）
            XmlProtoConfig cfg = ctx.attrs().get(XmlProtoConfig.ATTR) instanceof XmlProtoConfig c
                    ? c : XmlProtoConfig.DEFAULT;
            // ack 渲染不 SOAP 化（D-SOAP-6）：ack 是回给回调方的回执，与出站报文是两件事
            boolean ackMode = Boolean.TRUE.equals(ctx.attrs().get(ATTR_ACK_MODE));
            boolean soap = cfg.isSoap() && !ackMode;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // ⚠ 不变式：writer 字节编码与声明必须【同源】（均取 cfg.encoding）——
            //   实测 Woodstox 的 writeStartDocument(encoding,…) 仅在 writer 未指定编码时生效，
            //   故本行才是编码权威；两者不一致会让声明“说谎”
            XMLStreamWriter w = outputFactory.createXMLStreamWriter(out, cfg.encoding());
            w.writeStartDocument(cfg.encoding(), cfg.version());
            if (!(ctx.payload().root() instanceof ObjectNode)) {
                // XML 文档单根约束（评审 N3）：数组/标量根无合法 XML 表示，明确报错而非产出非法多根文档
                throw new BizException(50000, "报文编码失败：XML 根节点必须为对象");
            }
            if (soap) {
                // SOAP 包裹（B2）：Envelope → Body → 业务元素；envelope 命名空间由 type 决定，前缀无语义
                String prefix = cfg.envelopePrefix();
                String envNs = cfg.type().envelopeNs();
                w.writeStartElement(prefix, "Envelope", envNs);
                w.writeNamespace(prefix, envNs);
                w.writeStartElement(prefix, "Body", envNs);
                writeRoot(w, cfg.root(), cfg, ctx.payload().root());
                w.writeEndElement();   // </soap:Body>
                w.writeEndElement();   // </soap:Envelope>
            } else {
                // 根元素：显式 xmlRoot（仅 ack 会设）优先；否则取配置 root
                String root = ctx.attrs().get("xmlRoot") instanceof String s ? s : cfg.root();
                writeRoot(w, root, cfg, ctx.payload().root());
            }
            w.writeEndDocument();
            w.flush();
            w.close();
            ctx.outbound().body(out.toByteArray());
            applyXmlHeaders(ctx, cfg, soap);
            return ctx;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50000, "报文编码失败：" + e.getMessage());
        }
    }

    /**
     * 报文头（B2）：POX 保持 `application/xml`（= 改造前行为，零回归）；SOAP 按线协议分派。
     * <p>1.1：`text/xml` + **`SOAPAction` 头**（有 action 才发）；1.2：`application/soap+xml; action=`（**无** SOAPAction 头）。
     * <p>同时给 spec 置 `soapVersion` —— 供 UpstreamInvoker 在 5xx 时判断“要不要按 SOAP Fault 解析响应体”。
     */
    private void applyXmlHeaders(AdapterContext ctx, XmlProtoConfig cfg, boolean soap) {
        if (!soap) {
            ctx.outbound().header("Content-Type", "application/xml");
            ctx.outbound().header("Accept", "application/xml");
            return;
        }
        String enc = cfg.encoding();
        ctx.outbound().soapVersion(cfg.type().soapVersion());
        if ("1.2".equals(cfg.type().soapVersion())) {
            String action = cfg.soap().action();
            String ct = "application/soap+xml; charset=" + enc
                    + (action == null || action.isBlank() ? "" : "; action=\"" + action + "\"");
            ctx.outbound().header("Content-Type", ct);
            ctx.outbound().header("Accept", "application/soap+xml");
        } else {
            ctx.outbound().header("Content-Type", "text/xml; charset=" + enc);
            ctx.outbound().header("Accept", "text/xml");
            String soapAction = cfg.soap().soapActionHeader();
            if (soapAction != null) {
                ctx.outbound().header("SOAPAction", soapAction);
            }
        }
    }

    /**
     * 写根元素（可带命名空间）：配了 namespace 时用 StAX 三参 API + writeNamespace
     * （探针 #2/#3 实证：`writeStartElement(prefix,local,ns)` + `writeNamespace` 才能正确输出 xmlns）；
     * 子元素交给常规 {@link #writeNodeQuiet}（默认命名空间下自动继承，带前缀时同前缀生效）。
     */
    private void writeRoot(XMLStreamWriter w, String name, XmlProtoConfig cfg, UnifiedModel.UNode node)
            throws XMLStreamException {
        if (node instanceof ObjectNode obj) {
            if (cfg.hasNamespace()) {
                String prefix = cfg.nsPrefix() == null ? "" : cfg.nsPrefix();
                w.writeStartElement(prefix, name, cfg.nsUri());
                w.writeNamespace(prefix, cfg.nsUri());
            } else {
                w.writeStartElement(name);   // = 改造前行为（零回归）
            }
            writeAttributes(w, obj.attributes());
            obj.fields().forEach((k, v) -> writeNodeQuiet(w, k, v));
            w.writeEndElement();
            return;
        }
        writeNode(w, name, node);   // 非对象根（数组/标量）：保持既有行为
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
