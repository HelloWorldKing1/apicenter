package com.deepx.apicenter.engine;

import com.deepx.apicenter.exception.BizException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 协议参数（interface.protocol_params）解析与校验单测 —— 《XML声明配置设计方案.md》v4.2 · B1。
 *
 * <p>核心纪律：**非法配置一律 40001，且不静默回落默认**（否则"配错了但看起来生效"是最难发现的一类失效）。
 * 未知键拒绝（防拼错）、`soap` 段明确拒绝（B2 延后）、`encoding` 拒绝 UTF-16/32（非 ASCII 兼容）。
 */
class XmlProtoConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------- 默认与缺省（零回归的保证） ----------

    @Test
    void 未配置或空壳_回落内置默认() {
        for (String json : new String[]{null, "", "   ", "{}", "null", "{\"xml\":null}"}) {
            XmlProtoConfig c = XmlProtoConfig.of(json, mapper);
            assertThat(c).as("输入=%s", json).isEqualTo(XmlProtoConfig.DEFAULT);
        }
        // 内置默认必须与改造前硬编码值逐字节一致
        assertThat(XmlProtoConfig.DEFAULT.version()).isEqualTo("1.0");
        assertThat(XmlProtoConfig.DEFAULT.encoding()).isEqualTo("UTF-8");
        assertThat(XmlProtoConfig.DEFAULT.root()).isEqualTo("request");
        assertThat(XmlProtoConfig.DEFAULT.hasNamespace()).isFalse();
    }

    @Test
    void 解析完整配置() {
        XmlProtoConfig c = XmlProtoConfig.of("""
                {"xml":{"version":"1.1","encoding":"GBK","root":"QueryRequest",
                        "namespace":{"prefix":"ns","uri":"http://example.com/svc"}}}""", mapper);
        assertThat(c.version()).isEqualTo("1.1");
        assertThat(c.encoding()).isEqualTo("GBK");
        assertThat(c.root()).isEqualTo("QueryRequest");
        assertThat(c.nsPrefix()).isEqualTo("ns");
        assertThat(c.nsUri()).isEqualTo("http://example.com/svc");
        assertThat(c.hasNamespace()).isTrue();
    }

    @Test
    void 默认命名空间_前缀为空串() {
        XmlProtoConfig c = XmlProtoConfig.of(
                "{\"xml\":{\"namespace\":{\"uri\":\"http://x\"}}}", mapper);
        assertThat(c.hasNamespace()).isTrue();
        assertThat(c.nsPrefix()).isEmpty();          // "" = xmlns="…"（默认命名空间）
        assertThat(c.version()).isEqualTo("1.0");    // 未配的项各取默认
        assertThat(c.root()).isEqualTo("request");
    }

    // ---------- 未知键（防"配错了但看起来生效"） ----------

    @Test
    void 顶层未知键_40001() {
        assertThatThrownBy(() -> XmlProtoConfig.of("{\"jsoon\":{}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知键");
    }

    @Test
    void xml内拼错键_40001_不得静默回落默认() {
        // rootEelement 拼错：若忽略未知键，会静默用默认 root=request —— 最难发现的失效形态
        assertThatThrownBy(() -> XmlProtoConfig.of("{\"xml\":{\"rootEelement\":\"X\"}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知键")
                .hasMessageContaining("rootEelement");
    }

    @Test
    void namespace内未知键_40001() {
        assertThatThrownBy(() -> XmlProtoConfig.of(
                "{\"xml\":{\"namespace\":{\"uri\":\"http://x\",\"prefx\":\"ns\"}}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知键");
    }

    @Test
    void soap段_无type时被拒_互斥_B2已支持SOAP但必须显式声明type() {
        // B1 → B2 的语义变更（本用例反向改写）：B1 时代 soap 段是“未知键 → 带 B2 延后提示”；
        // B2 起 soap 段已支持，但**必须先用 type 声明类型**（方案 A：type 是唯一真相）——
        // 缺 type（= POX）时出现 soap 段 → 40001 互斥。
        assertThatThrownBy(() -> XmlProtoConfig.of(
                "{\"xml\":{\"soap\":{\"version\":\"1.1\"}}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("POX 时不允许出现 soap 段");
    }

    // ---------- 值域白名单 ----------

    @Test
    void version白名单() {
        assertThat(XmlProtoConfig.of("{\"xml\":{\"version\":\"1.1\"}}", mapper).version()).isEqualTo("1.1");
        // 探针 #6：Woodstox 只接受 1.0/1.1，漏到运行期会被吞成 50000 → 必须保存期 40001
        assertThatThrownBy(() -> XmlProtoConfig.of("{\"xml\":{\"version\":\"1.2\"}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅支持 1.0 / 1.1");
    }

    @Test
    void encoding拒绝UTF16族_保留ASCII安全前提() {
        for (String bad : new String[]{"UTF-16", "utf-16", "UTF-16LE", "UTF-32"}) {
            assertThatThrownBy(() -> XmlProtoConfig.of(
                    "{\"xml\":{\"encoding\":\"" + bad + "\"}}", mapper))
                    .as("encoding=%s 应被拒绝", bad)
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("非 ASCII 兼容");
        }
    }

    @Test
    void encoding接受ASCII兼容字符集_并拒绝不存在的字符集() {
        assertThat(XmlProtoConfig.of("{\"xml\":{\"encoding\":\"GB18030\"}}", mapper).encoding())
                .isEqualTo("GB18030");
        assertThat(XmlProtoConfig.of("{\"xml\":{\"encoding\":\"Big5\"}}", mapper).encoding())
                .isEqualTo("Big5");
        assertThatThrownBy(() -> XmlProtoConfig.of("{\"xml\":{\"encoding\":\"NO-SUCH-CHARSET\"}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是受支持的字符集");
    }

    // ---------- 根元素与命名空间 ----------

    @Test
    void root非合法XML元素名_40001() {
        for (String bad : new String[]{"a b", "ns:QueryRequest", "1abc", "a<b", "请求"}) {
            assertThatThrownBy(() -> XmlProtoConfig.of("{\"xml\":{\"root\":\"" + bad + "\"}}", mapper))
                    .as("root=%s 应被拒绝", bad)
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不是合法 XML 元素名");
        }
    }

    @Test
    void root空串_40001() {
        // 「配了但没值」一律 40001（把空串当“不要根元素”是典型误解，静默回落默认就是“配错了但看起来生效”）
        assertThatThrownBy(() -> XmlProtoConfig.of("{\"xml\":{\"root\":\"\"}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("存在但为空值");
        // version / encoding 同口径
        assertThatThrownBy(() -> XmlProtoConfig.of("{\"xml\":{\"version\":\"  \"}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("存在但为空值");
        // 但键【缺失】= 用内置默认（不报错）
        assertThat(XmlProtoConfig.of("{\"xml\":{}}", mapper)).isEqualTo(XmlProtoConfig.DEFAULT);
    }

    @Test
    void prefix空白是有语义的_默认命名空间() {
        XmlProtoConfig c = XmlProtoConfig.of(
                "{\"xml\":{\"namespace\":{\"prefix\":\"\",\"uri\":\"http://x\"}}}", mapper);
        assertThat(c.hasNamespace()).isTrue();
        assertThat(c.nsPrefix()).isEmpty();
    }

    @Test
    void namespace非法_40001() {
        assertThatThrownBy(() -> XmlProtoConfig.of("{\"xml\":{\"namespace\":{}}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("namespace.uri 不能为空");
        assertThatThrownBy(() -> XmlProtoConfig.of(
                "{\"xml\":{\"namespace\":{\"uri\":\"http://x\",\"prefix\":\"a b\"}}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是合法前缀");
        assertThatThrownBy(() -> XmlProtoConfig.of(
                "{\"xml\":{\"namespace\":\"http://x\"}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须是 JSON 对象");
    }

    @Test
    void 非JSON对象与非法JSON_40001() {
        assertThatThrownBy(() -> XmlProtoConfig.of("[1,2]", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须是 JSON 对象");
        assertThatThrownBy(() -> XmlProtoConfig.of("{不是JSON", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    void hasXmlSection_用于方向校验() {
        assertThat(XmlProtoConfig.hasXmlSection("{\"xml\":{\"root\":\"X\"}}")).isTrue();
        assertThat(XmlProtoConfig.hasXmlSection(null)).isFalse();
        assertThat(XmlProtoConfig.hasXmlSection("{}")).isFalse();
    }

    // ---------- B2：xml.type 为唯一真相（方案 A） ----------

    @Test
    void type缺省视为POX_B1历史数据零迁移() {
        // B1 时期写入的 protocol_params 【没有 type】→ 必须仍按 POX 解释（否则存量接口全乱）
        XmlProtoConfig c = XmlProtoConfig.of(
                "{\"xml\":{\"version\":\"1.1\",\"root\":\"QueryRequest\"}}", mapper);
        assertThat(c.type()).isEqualTo(XmlProtoConfig.Type.POX);
        assertThat(c.isSoap()).isFalse();
        assertThat(c.soap()).isNull();
        assertThat(c.version()).isEqualTo("1.1");          // 声明版本与 SOAP 版本无关
        assertThat(c.root()).isEqualTo("QueryRequest");
    }

    @Test
    void type白名单外_40001() {
        assertThatThrownBy(() -> XmlProtoConfig.of("{\"xml\":{\"type\":\"SOAP_2_0\"}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("type 仅支持");
    }

    @Test
    void SOAP类型_解析成功且soap段默认可省() {
        XmlProtoConfig c = XmlProtoConfig.of(
                "{\"xml\":{\"type\":\"SOAP_1_1\",\"root\":\"Add\",\"namespace\":{\"uri\":\"http://tempuri.org/\"}}}", mapper);
        assertThat(c.type()).isEqualTo(XmlProtoConfig.Type.SOAP_1_1);
        assertThat(c.type().soapVersion()).isEqualTo("1.1");
        assertThat(c.type().envelopeNs()).isEqualTo("http://schemas.xmlsoap.org/soap/envelope/");
        assertThat(c.isSoap()).isTrue();
        // soap 段可省 → 全默认：action 空（实测 6/6 服务不强制）、前缀 soap、解包开
        assertThat(c.soap()).isEqualTo(XmlProtoConfig.SoapConfig.DEFAULT);
        assertThat(c.envelopePrefix()).isEqualTo("soap");
        assertThat(c.unwrapResponse()).isTrue();
        assertThat(c.soap().soapActionHeader()).as("action 为空 → 不发 SOAPAction 头").isNull();
    }

    @Test
    void SOAP类型_解析soap段全量() {
        XmlProtoConfig c = XmlProtoConfig.of("""
                {"xml":{"type":"SOAP_1_2","root":"Add",
                        "soap":{"action":"http://tempuri.org/Add","envelopePrefix":"env",
                                 "unwrapResponse":false}}}""", mapper);
        assertThat(c.type().soapVersion()).isEqualTo("1.2");
        assertThat(c.type().envelopeNs()).isEqualTo("http://www.w3.org/2003/05/soap-envelope");
        assertThat(c.soap().action()).isEqualTo("http://tempuri.org/Add");
        assertThat(c.soap().soapActionHeader()).isEqualTo("\"http://tempuri.org/Add\"");
        assertThat(c.envelopePrefix()).isEqualTo("env");
        assertThat(c.unwrapResponse()).isFalse();
    }

    @Test
    void POX带soap键_40001_互斥() {
        // 方案 A 的要旨：版本由 type 表达，POX 下出现 soap 段必为配错
        assertThatThrownBy(() -> XmlProtoConfig.of(
                "{\"xml\":{\"type\":\"POX\",\"soap\":{\"action\":\"x\"}}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("POX 时不允许出现 soap 段");
        // 连 type 都不写（缺省 POX）也禁止 —— 否则又回到“段存在即启用”的隐式双真相
        assertThatThrownBy(() -> XmlProtoConfig.of("{\"xml\":{\"soap\":{}}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("POX 时不允许出现 soap 段");
    }

    @Test
    void soap段里写version_40001_防双真相() {
        assertThatThrownBy(() -> XmlProtoConfig.of(
                "{\"xml\":{\"type\":\"SOAP_1_1\",\"soap\":{\"version\":\"1.1\"}}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知键")
                .hasMessageContaining("version");
    }

    @Test
    void soap段内未知键与非法前缀_40001() {
        assertThatThrownBy(() -> XmlProtoConfig.of(
                "{\"xml\":{\"type\":\"SOAP_1_1\",\"soap\":{\"actoin\":\"x\"}}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知键");
        assertThatThrownBy(() -> XmlProtoConfig.of(
                "{\"xml\":{\"type\":\"SOAP_1_1\",\"soap\":{\"envelopePrefix\":\"a b\"}}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("envelopePrefix");
    }

    @Test
    void SOAP类型下的root与namespace与POX同一套规则() {
        // root 含冒号（前缀该由 namespace.prefix 表达）—— SOAP 下同样拒绝
        assertThatThrownBy(() -> XmlProtoConfig.of(
                "{\"xml\":{\"type\":\"SOAP_1_1\",\"root\":\"ns:Add\"}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是合法 XML 元素名");
        // encoding 白名单同样适用
        assertThatThrownBy(() -> XmlProtoConfig.of(
                "{\"xml\":{\"type\":\"SOAP_1_1\",\"encoding\":\"UTF-16\"}}", mapper))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非 ASCII 兼容");
    }
}
