package com.deepx.apicenter.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Optional;

/**
 * SOAP Fault 解析（B2：《B2完整SOAP开发计划.md》§2.3 / 《XML声明配置设计方案.md》§5.3）。
 *
 * <p>只在 {@code UpstreamInvoker} 遇到 <b>5xx</b> 且请求规格声明了 SOAP 时调用，用于区分
 * <b>「供应商服务端故障」</b>（应重试/补偿）与<b>「我们请求错了」</b>（确定性错误，应死信不重试）。
 *
 * <p><b>判据（严格结构匹配，不做字符串嗅探）</b>：
 * 根元素 localName 必须为 {@code Envelope} 且其命名空间为 SOAP 1.1 / 1.2 之一；
 * {@code Envelope/Body/Fault} 存在才算 Fault。
 *
 * <p><b>两个实测事实</b>（决定了实现口径）：
 * <ul>
 *   <li>方言按<b>报文自身的 envelope 命名空间</b>判断，<b>不看配置的 type</b>——因为
 *       向 1.1-only 服务发 1.2 报文时，服务端会用 <b>1.1 结构</b>回 {@code VersionMismatch}（已实测，见样本集 C-2）；</li>
 *   <li>前缀无语义（样本里 1.2 的 Fault 也可能用 {@code soap:} 前缀绑定 2003/05 命名空间）→ 一律按命名空间 + localName 匹配。</li>
 * </ul>
 *
 * <p>实现用 JDK 自带 DOM（禁用 DTD/外部实体防 XXE）—— 仅解析服务端回给我们的 5xx 报文，量小、代码短、无新依赖。
 */
public final class SoapFaultParser {

    private static final Logger log = LoggerFactory.getLogger(SoapFaultParser.class);

    private static final String NS_11 = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String NS_12 = "http://www.w3.org/2003/05/soap-envelope";

    /** 客户端类 faultcode（确定性错误 → 死信不重试）：1.1 的 Client/VersionMismatch/MustUnderstand、1.2 的 Sender */
    private static final java.util.Set<String> CLIENT_CODES =
            java.util.Set.of("client", "sender", "versionmismatch", "mustunderstand");

    private SoapFaultParser() {
    }

    /**
     * 解析结果。
     *
     * @param soapVersion "1.1" / "1.2"（按报文自身命名空间判定）
     * @param code        原始 faultcode / Code.Value（如 {@code soap:Client}、{@code soap:Sender}、{@code soap:VersionMismatch}）
     * @param message     faultstring / Reason.Text（可能很长，落库前需截断）
     */
    public record Fault(String soapVersion, String code, String message) {

        /** 归一到 localName 后的代码（如 {@code soap:Client} → {@code Client}） */
        public String normalizedCode() {
            if (code == null) {
                return "";
            }
            String c = code.trim();
            int colon = c.lastIndexOf(':');
            if (colon >= 0) {
                c = c.substring(colon + 1);
            }
            return c;
        }

        /** 是否**客户端类**（→ 死信、不重试、不计熔断）。未识别的代码一律返回 false（保守：宁可重试，不可丢单） */
        public boolean clientSide() {
            return CLIENT_CODES.contains(normalizedCode().toLowerCase(Locale.ROOT));
        }
    }

    /** 解析 5xx 响应体；非 SOAP Fault → {@link Optional#empty()}（调用方按普通 5xx 处理） */
    public static Optional<Fault> parse(byte[] body) {
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            trySet(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySet(f, "http://xml.org/sax/features/external-general-entities", false);
            trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            Document doc;
            try (ByteArrayInputStream in = new ByteArrayInputStream(body)) {
                doc = b.parse(in);
            }
            Element root = doc.getDocumentElement();
            if (root == null || !"Envelope".equals(localName(root))) {
                return Optional.empty();
            }
            String ns = root.getNamespaceURI();
            String version;
            if (NS_11.equals(ns)) {
                version = "1.1";
            } else if (NS_12.equals(ns)) {
                version = "1.2";
            } else {
                return Optional.empty();   // 不是 SOAP 信封 → 不误判
            }
            Element bodyEl = firstChild(root, "Body");
            if (bodyEl == null) {
                return Optional.empty();
            }
            Element fault = firstChild(bodyEl, "Fault");
            if (fault == null) {
                return Optional.empty();
            }
            return Optional.of("1.1".equals(version) ? parse11(fault) : parse12(fault));
        } catch (Exception e) {
            log.warn("SOAP Fault 解析失败（按普通 5xx 处理）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 1.1：{@code <Fault><faultcode/><faultstring/><detail/></Fault>} */
    private static Fault parse11(Element fault) {
        return new Fault("1.1", textOf(fault, "faultcode"), textOf(fault, "faultstring"));
    }

    /** 1.2：{@code <Fault><Code><Value/></Code><Reason><Text/></Reason></Fault>} */
    private static Fault parse12(Element fault) {
        Element code = firstChild(fault, "Code");
        String value = code == null ? null : textOf(code, "Value");
        Element reason = firstChild(fault, "Reason");
        String text = null;
        if (reason != null) {
            Element t = firstChild(reason, "Text");
            text = t == null ? null : t.getTextContent();
        }
        return new Fault("1.2", value, text);
    }

    // ---------- DOM 小工具（一律按 localName 匹配，忽略前缀） ----------

    private static String localName(Element e) {
        return e.getLocalName() != null ? e.getLocalName() : e.getTagName();
    }

    private static Element firstChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e && localName.equals(localName(e))) {
                return e;
            }
        }
        return null;
    }

    /** 直接子元素文本（不存在 → null） */
    private static String textOf(Element parent, String childLocalName) {
        if (parent == null) {
            return null;
        }
        Element child = firstChild(parent, childLocalName);
        return child == null ? null : child.getTextContent();
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Exception ignored) {
            // 实现不支持该属性时忽略（与 XmlProtocolAdapter 的 XXE 双保险同策略）
        }
    }
}
