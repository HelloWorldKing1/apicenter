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
 * 接口级协议参数（`interface.protocol_params`）的解析与校验（《XML声明配置设计方案.md》v4.2 · B1）。
 *
 * <p><b>JSON 形态</b>：
 * <pre>{@code
 * { "xml": {
 *     "version":  "1.0",            // 1.0 | 1.1（默认 1.0）
 *     "encoding": "UTF-8",          // 声明用字符集，须「JDK 支持 且 ASCII 兼容」（默认 UTF-8）
 *     "root":     "QueryRequest",   // 业务根元素名（默认 request）
 *     "namespace": { "prefix": "ns", "uri": "http://example.com/svc" }   // 可选
 * } } }</pre>
 *
 * <p><b>为什么要求「ASCII 兼容」</b>：实测（Woodstox 6.4.0）设 factory encoding=GBK 时，非 ASCII 文本被写成
 * <b>数字字符引用</b>（`&#x4e2d;`）而字节保持 ASCII 安全 —— 因此可配 `encoding` 的收益是
 * <b>「让声明与供应商期望一致」</b>，不是「改变字节」。而 `UTF-16`/`UTF-32` 会产出原生非 ASCII 字节（含 BOM/NUL），
 * 会打破该前提、并让 `call_log`（固定 UTF-8 解码）失真 → <b>显式拒绝</b>。
 *
 * <p><b>校验纪律（防「配错了但看起来生效」）</b>：
 * <ul>
 *   <li><b>未知键 → 40001</b>（不忽略）：拼错 `rootEelement` 会静默回落默认，是最难发现的一类失效；</li>
 *   <li>`soap` 段本期<b>未实现</b>（B2 延后）→ 同样 40001 并给出明确提示，避免「配了没生效」；</li>
 *   <li>`root` 为空串 → 40001（本期不支持「Body 直放字段」）；</li>
 *   <li>非法值 → <b>40001</b>，不得漏到运行期被 `catch(Exception)` 吞成 `50000`。</li>
 * </ul>
 *
 * <p>缺省 = {@link #DEFAULT}（`1.0` / `UTF-8` / `request` / 无命名空间）= 硬编码前的行为 ⇒ <b>零回归</b>。
 */
public record XmlProtoConfig(String version, String encoding, String root, String nsPrefix, String nsUri) {

    /** 内置默认：与改造前硬编码值逐字节一致（零回归的保证） */
    public static final XmlProtoConfig DEFAULT = new XmlProtoConfig("1.0", "UTF-8", "request", null, null);

    /** 链上下文属性键（`ctx.attrs`）：协议适配器从这里读；由 ChainEngine 装配期烘焙后注入 */
    public static final String ATTR = "xmlProtoConfig";

    private static final Set<String> VERSIONS = Set.of("1.0", "1.1");

    /** 非 ASCII 兼容字符集：会产原生非 ASCII 字节（BOM/NUL），破坏「字节 ASCII 安全」前提 → 拒绝 */
    private static final Set<String> NON_ASCII_COMPATIBLE =
            Set.of("UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32", "UTF-32LE", "UTF-32BE");

    /** 顶层允许的键（B2 的 `soap` / 将来的 `json` 不在其中 → 未知键机制会明确拒绝） */
    private static final Set<String> KNOWN_TOP_KEYS = Set.of("xml");
    private static final Set<String> KNOWN_XML_KEYS = Set.of("version", "encoding", "root", "namespace");
    private static final Set<String> KNOWN_NS_KEYS = Set.of("prefix", "uri");

    /** 简化 NCName（禁冒号 —— 前缀只由 namespace.prefix 表达） */
    private static final Pattern NCNAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9._-]*$");

    /** 是否配了命名空间 */
    public boolean hasNamespace() {
        return nsUri != null && !nsUri.isBlank();
    }

    /**
     * 解析并校验（空 / blank / `{}` / `{"xml":null}` → {@link #DEFAULT}）。
     *
     * @throws BizException 40001 —— 任何非法配置（未知键 / 白名单外取值 / 非法名字或 URI / 非 JSON 对象）
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
            String uri = text(ns, "uri", "");
            if (uri.isBlank()) {
                throw BizException.fieldInvalid("协议参数 namespace.uri 不能为空");
            }
            try {
                URI.create(uri);
            } catch (Exception e) {
                throw BizException.fieldInvalid("协议参数 namespace.uri 不是合法 URI：" + uri);
            }
            String prefix = text(ns, "prefix", "");
            if (!prefix.isBlank() && !NCNAME.matcher(prefix).matches()) {
                throw BizException.fieldInvalid("协议参数 namespace.prefix 不是合法前缀：" + prefix);
            }
            nsUri = uri;
            nsPrefix = prefix.isBlank() ? "" : prefix;   // "" = 默认命名空间（xmlns="…"）
        }
        return new XmlProtoConfig(version, encoding, name, nsPrefix, nsUri);
    }

    /** 是否含 `xml` 段（供「JSON 协议的接口配了 xml 段 → 40001」判定） */
    public static boolean hasXmlSection(String json) {
        return json != null && json.contains("\"xml\"");
    }

    // ---------- 私有 ----------

    /**
     * 未知键一律拒绝：忽略未知键会让拼写错误（如 rootEelement）静默回落默认 —— 即「配错了但看起来生效」。
     * `soap` 段属 B2（延后实现），给出专门提示，避免被误当拼写错误。
     */
    private static void assertNoUnknownKeys(JsonNode obj, Set<String> known, String where) {
        Set<String> unknown = new LinkedHashSet<>();
        obj.properties().forEach(e -> {
            if (!known.contains(e.getKey())) {
                unknown.add(e.getKey());
            }
        });
        if (unknown.isEmpty()) {
            return;
        }
        String hint = unknown.contains("soap")
                ? "（SOAP 段尚未支持：B2 延后实施，待真实 SOAP 供应商样本 —— 见《XML声明配置设计方案.md》§12 Q19）"
                : "";
        throw BizException.fieldInvalid(where + " 含未知键：" + unknown + hint);
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

    /**
     * 取值（严格口径）：键**缺失 / null → 内置默认**；键**存在但为空白 → 40001**。
     *
     * <p>为何不把空白也当成“用默认”：`"root":""` 这类写法很容易被理解为“我不要根元素”，
     * 静默回落成 request 就是典型的“配错了但看起来生效”。想用默认就**别写这个键**。
     *
     * <p>特例：`namespace.prefix` 的空白是**有语义的**（= 默认命名空间），由 {@link #text} 处理。
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

    /** 取值（宽松口径，仅用于 prefix）：键缺失 / null / 空白 → 默认值 */
    private static String text(JsonNode obj, String key, String def) {
        JsonNode n = obj.get(key);
        if (n == null || n.isNull() || n.asText().isBlank()) {
            return def;
        }
        return n.asText().trim();
    }
}
