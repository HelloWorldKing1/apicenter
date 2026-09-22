package com.deepx.apicenter.engine;

import com.deepx.apicenter.exception.BizException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 接口级协议参数（`interface.protocol_params`）的解析与校验。
 * 依据《XML声明配置设计方案.md》v4.4（B1 已落地；B2 增量）与《B2完整SOAP开发计划.md》§2.2/§2.5。
 *
 * <p><b>JSON 形态</b>：
 * <pre>{@code
 * { "xml": {
 *     "type":     "SOAP_1_1",          // ★ 唯一真相：POX（默认）/ SOAP_1_1 / SOAP_1_2
 *     "version":  "1.0",               // XML 声明版本，与 SOAP 版本无关
 *     "encoding": "UTF-8",             // 声明用字符集，须「JDK 支持 且 ASCII 兼容」
 *     "root":     "Add",               // POX=文档根；SOAP=Body 内业务元素
 *     "namespace": { "prefix": "ns", "uri": "http://example.com/svc" },
 *     "soap": {                        // 仅 type=SOAP_* 允许（POX 带它 → 40001 互斥）
 *       "action": "http://tempuri.org/Add",     // 可选
 *       "envelopePrefix": "soap",               // 默认 soap
 *       "unwrapResponse": true                  // 默认 true
 *     }
 * } } }</pre>
 *
 * <p><b>为什么 `type` 是权威</b>（方案 A，2026-09-21 定稿）：SOAP 的版本决定「请求怎么包 / 响应怎么解 / Fault 怎么读」，
 * 若靠“`soap` 段是否存在”隐式推断，则 `soap` 里再放 `version` 就会形成<b>双真相</b>。
 * 故 `soap` 段降为<b>该类型的配置载体</b>且<b>不含 version</b>；`type` 缺省 = `POX`
 * ⇒ <b>B1 时期的历史数据（无 `type`）零迁移</b>。
 *
 * <p><b>校验纪律</b>（防“配错了但看起来生效”，与 B1 一致）：未知键 → 40001；白名单外取值 → 40001；
 * `POX` + `soap` 键 → 40001（互斥）；`encoding` 拒绝 UTF-16/UTF-32（非 ASCII 兼容）。
 */
public record XmlProtoConfig(Type type, String version, String encoding, String root,
                             String nsPrefix, String nsUri, SoapConfig soap) {

    /** XML 交互类型（**唯一真相**）：决定请求包裹 / 响应解包 / Fault 方言 */
    public enum Type {
        /** 普通 XML（自定义契约 / POX）：出站 = 文档根 + 字段；响应原样解包 */
        POX(null, null),
        /** SOAP 1.1：envelope ns + `Content-Type: text/xml` + `SOAPAction` 头 */
        SOAP_1_1("1.1", "http://schemas.xmlsoap.org/soap/envelope/"),
        /** SOAP 1.2：envelope ns + `application/soap+xml; action=`（无 SOAPAction 头） */
        SOAP_1_2("1.2", "http://www.w3.org/2003/05/soap-envelope");

        private final String soapVersion;
        private final String envelopeNs;

        Type(String soapVersion, String envelopeNs) {
            this.soapVersion = soapVersion;
            this.envelopeNs = envelopeNs;
        }

        public boolean isSoap() {
            return soapVersion != null;
        }

        /** "1.1" / "1.2"（POX 为 null）—— 供请求规格与 Fault 探测使用 */
        public String soapVersion() {
            return soapVersion;
        }

        public String envelopeNs() {
            return envelopeNs;
        }
    }

    /** SOAP 子配置（仅 `type=SOAP_*` 存在；**不含 version**——版本由 {@link Type} 携带） */
    public record SoapConfig(String action, String envelopePrefix, boolean unwrapResponse) {
        /** 默认：action 空（实测 6/6 公开服务不强制）、envelope 前缀 soap、解包开 */
        public static final SoapConfig DEFAULT = new SoapConfig(null, "soap", true);

        /** SOAPAction 头值（1.1 用）：非空时按规范加引号；空 → null（不发该头） */
        public String soapActionHeader() {
            return action == null || action.isBlank() ? null : "\"" + action + "\"";
        }
    }

    /** 内置默认：与改造前硬编码值逐字节一致（零回归的保证） */
    public static final XmlProtoConfig DEFAULT =
            new XmlProtoConfig(Type.POX, "1.0", "UTF-8", "request", null, null, null);

    /** 便捷工厂：**POX** 配置（B1 时代的等价形态；测试与内部构造常用） */
    public static XmlProtoConfig pox(String version, String encoding, String root, String nsPrefix, String nsUri) {
        return new XmlProtoConfig(Type.POX, version, encoding, root, nsPrefix, nsUri, null);
    }

    /** 便捷工厂：**SOAP** 配置（soap 段用默认：action 空 / envelopePrefix soap / 解包开） */
    public static XmlProtoConfig soap(Type type, String version, String encoding, String root,
                                      String nsPrefix, String nsUri) {
        return new XmlProtoConfig(type, version, encoding, root, nsPrefix, nsUri, SoapConfig.DEFAULT);
    }

    /** 链上下文属性键（`ctx.attrs`）：协议适配器从这里读；由 ChainEngine 装配期烘焙后注入 */
    public static final String ATTR = "xmlProtoConfig";

    private static final Set<String> VERSIONS = Set.of("1.0", "1.1");
    private static final Set<String> TYPES = Set.of("POX", "SOAP_1_1", "SOAP_1_2");

    /** 非 ASCII 兼容字符集：会产原生非 ASCII 字节（BOM/NUL），破坏「字节 ASCII 安全」前提 → 拒绝 */
    private static final Set<String> NON_ASCII_COMPATIBLE =
            Set.of("UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32", "UTF-32LE", "UTF-32BE");

    private static final Set<String> KNOWN_TOP_KEYS = Set.of("xml");
    private static final Set<String> KNOWN_XML_KEYS =
            Set.of("type", "version", "encoding", "root", "namespace", "soap");
    private static final Set<String> KNOWN_NS_KEYS = Set.of("prefix", "uri");
    private static final Set<String> KNOWN_SOAP_KEYS =
            Set.of("action", "envelopePrefix", "unwrapResponse");

    /** 简化 NCName（禁冒号 —— 前缀只由 namespace.prefix / soap.envelopePrefix 表达） */
    private static final Pattern NCNAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9._-]*$");

    /** 是否配了命名空间 */
    public boolean hasNamespace() {
        return nsUri != null && !nsUri.isBlank();
    }

    public boolean isSoap() {
        return type().isSoap();
    }

    /** SOAP envelope 前缀（仅 SOAP 有效） */
    public String envelopePrefix() {
        return soap == null ? null : soap.envelopePrefix();
    }

    /** 响应是否解包 Envelope/Body（仅 SOAP 有效） */
    public boolean unwrapResponse() {
        return soap != null && soap.unwrapResponse();
    }

    /**
     * 解析并校验（空 / blank / `{}` / `{"xml":null}` → {@link #DEFAULT}）。
     *
     * @throws BizException 40001 —— 任何非法配置（未知键 / 白名单外取值 / 互斥冲突 / 非法名字或 URI / 非 JSON 对象）
     */
    public static XmlProtoConfig of(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return DEFAULT;
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw BizException.fieldInvalid("协议参数不是合法 JSON：" + e.getMessage());
        }
        if (root == null || root.isNull() || (root.isObject() && root.isEmpty())) {
            return DEFAULT;
        }
        if (!root.isObject()) {
            throw BizException.fieldInvalid("协议参数必须是 JSON 对象");
        }
        assertNoUnknownKeys(root, KNOWN_TOP_KEYS, "协议参数");

        JsonNode xml = root.get("xml");
        if (xml == null || xml.isNull()) {
            return DEFAULT; // 只配了空壳 → 内置默认
        }
        if (!xml.isObject()) {
            throw BizException.fieldInvalid("协议参数 xml 段必须是 JSON 对象");
        }
        assertNoUnknownKeys(xml, KNOWN_XML_KEYS, "协议参数 xml");

        // ── type（唯一真相；缺省 POX ⇒ B1 历史数据零迁移）──
        String typeText = textStrict(xml, "type", Type.POX.name());
        Type type;
        try {
            type = Type.valueOf(typeText.toUpperCase());
        } catch (Exception e) {
            throw BizException.fieldInvalid("协议参数 type 仅支持 " + TYPES + "：" + typeText);
        }

        String version = textStrict(xml, "version", DEFAULT.version());
        if (!VERSIONS.contains(version)) {
            throw BizException.fieldInvalid("协议参数 version 仅支持 1.0 / 1.1：" + version);
        }
        String encoding = textStrict(xml, "encoding", DEFAULT.encoding());
        assertEncoding(encoding);
        String name = textStrict(xml, "root", DEFAULT.root());
        if (!NCNAME.matcher(name).matches()) {
            throw BizException.fieldInvalid(
                    "协议参数 root 不是合法 XML 元素名（禁空白 / 冒号 / 特殊字符；前缀请用 namespace.prefix 表达）：" + name);
        }

        String nsPrefix = null;
        String nsUri = null;
        JsonNode ns = xml.get("namespace");
        if (ns != null && !ns.isNull()) {
            if (!ns.isObject()) {
                throw BizException.fieldInvalid("协议参数 namespace 必须是 JSON 对象（{prefix, uri}）");
            }
            assertNoUnknownKeys(ns, KNOWN_NS_KEYS, "协议参数 namespace");
            nsUri = requireUri(text(ns, "uri", ""), "namespace.uri");
            String prefix = text(ns, "prefix", "");
            if (!prefix.isBlank() && !NCNAME.matcher(prefix).matches()) {
                throw BizException.fieldInvalid("协议参数 namespace.prefix 不是合法前缀：" + prefix);
            }
            nsPrefix = prefix.isBlank() ? "" : prefix;   // "" = 默认命名空间（xmlns="…"）
        }

        // ── soap 段：仅 SOAP_* 允许（互斥）──
        JsonNode soapNode = xml.get("soap");
        SoapConfig soapCfg = null;
        if (soapNode != null && !soapNode.isNull()) {
            if (!type.isSoap()) {
                throw BizException.fieldInvalid(
                        "协议参数 type=POX 时不允许出现 soap 段（互斥：SOAP 版本由 type 表达，避免双真相）");
            }
            if (!soapNode.isObject()) {
                throw BizException.fieldInvalid("协议参数 soap 段必须是 JSON 对象");
            }
            assertNoUnknownKeys(soapNode, KNOWN_SOAP_KEYS, "协议参数 soap");
            String action = text(soapNode, "action", "");
            String prefix = textStrict(soapNode, "envelopePrefix", SoapConfig.DEFAULT.envelopePrefix());
            if (!NCNAME.matcher(prefix).matches()) {
                throw BizException.fieldInvalid("协议参数 soap.envelopePrefix 不是合法前缀：" + prefix);
            }
            boolean unwrap = boolParam(soapNode, "unwrapResponse", SoapConfig.DEFAULT.unwrapResponse());
            soapCfg = new SoapConfig(action.isBlank() ? null : action, prefix, unwrap);
        } else if (type.isSoap()) {
            soapCfg = SoapConfig.DEFAULT;   // SOAP_* 未给 soap 段 → 全默认（action 可空）
        }
        return new XmlProtoConfig(type, version, encoding, name, nsPrefix, nsUri, soapCfg);
    }

    /** 是否含 `xml` 段（供「非 XML 出站的接口不该带协议参数」判定） */
    public static boolean hasXmlSection(String json) {
        return json != null && json.contains("\"xml\"");
    }

    // ---------- 私有 ----------

    /** 未知键一律拒绝：忽略会让拼写错误静默回落默认 —— 即「配错了但看起来生效」 */
    private static void assertNoUnknownKeys(JsonNode obj, Set<String> known, String where) {
        Set<String> unknown = new LinkedHashSet<>();
        obj.properties().forEach(e -> {
            if (!known.contains(e.getKey())) {
                unknown.add(e.getKey());
            }
        });
        if (!unknown.isEmpty()) {
            throw BizException.fieldInvalid(where + " 含未知键：" + unknown);
        }
    }

    /** 字符集校验：JDK 支持 且 ASCII 兼容（显式拒绝 UTF-16 / UTF-32） */
    private static void assertEncoding(String encoding) {
        if (NON_ASCII_COMPATIBLE.contains(encoding.toUpperCase())) {
            throw BizException.fieldInvalid("协议参数 encoding 不支持 " + encoding
                    + "（非 ASCII 兼容：会产出原生非 ASCII 字节，破坏「字节 ASCII 安全」前提）");
        }
        try {
            if (!Charset.isSupported(encoding)) {
                throw BizException.fieldInvalid("协议参数 encoding 不是受支持的字符集：" + encoding);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.fieldInvalid("协议参数 encoding 非法：" + encoding);
        }
    }

    private static String requireUri(String uri, String field) {
        if (uri == null || uri.isBlank()) {
            throw BizException.fieldInvalid("协议参数 " + field + " 不能为空");
        }
        try {
            URI.create(uri);
        } catch (Exception e) {
            throw BizException.fieldInvalid("协议参数 " + field + " 不是合法 URI：" + uri);
        }
        return uri;
    }

    /**
     * 取值（严格口径）：键**缺失 / null → 默认值**；键**存在但为空白 → 40001**。
     * “配了但没值”一律视为笔误（想用默认就别写这个键）。
     */
    private static String textStrict(JsonNode obj, String key, String def) {
        JsonNode n = obj.get(key);
        if (n == null || n.isNull()) {
            return def;
        }
        String v = n.asText().trim();
        if (v.isEmpty()) {
            throw BizException.fieldInvalid("协议参数 " + key + " 存在但为空值（如需用内置默认，请删除该键）");
        }
        return v;
    }

    /** 取值（宽松口径）：键缺失 / null / 空白 → 默认值（用于 prefix 这类“空串有语义”的字段） */
    private static String text(JsonNode obj, String key, String def) {
        JsonNode n = obj.get(key);
        if (n == null || n.isNull() || n.asText().isBlank()) {
            return def;
        }
        return n.asText().trim();
    }

    private static boolean boolParam(JsonNode obj, String key, boolean def) {
        JsonNode n = obj.get(key);
        if (n == null || n.isNull()) {
            return def;
        }
        return n.isBoolean() ? n.booleanValue() : Boolean.parseBoolean(n.asText());
    }
}
