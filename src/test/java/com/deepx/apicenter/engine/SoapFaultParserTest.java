package com.deepx.apicenter.engine;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOAP Fault 解析单测（B2）：**全部用仓库内的真实服务样本**（`src/test/resources/soap-samples/`）驱动，
 * 而不是手捏 XML —— 这样解析口径与真实世界一致（含 .NET 的全堆栈 faultstring、1.2 的 Code/Reason 结构、
 * 以及“1.1-only 服务对 1.2 报文回 1.1 结构 VersionMismatch”这一实测事实）。
 */
class SoapFaultParserTest {

    private static final Path SAMPLES = Path.of("src/test/resources/soap-samples");

    private static byte[] fixture(String rel) throws Exception {
        return Files.readAllBytes(SAMPLES.resolve(rel));
    }

    // ---------- 真实样本：三种 Fault 形态 ----------

    @Test
    void 样本_1_1_Client_Fault_判为客户端类() throws Exception {
        Optional<SoapFaultParser.Fault> f = SoapFaultParser.parse(fixture("fault/dneonline-client-11.resp.xml"));
        assertThat(f).isPresent();
        assertThat(f.get().soapVersion()).isEqualTo("1.1");
        assertThat(f.get().normalizedCode()).isEqualTo("Client");
        assertThat(f.get().clientSide()).isTrue();
        // 实测 faultstring 是 .NET 全堆栈（~1.5KB）—— 解析器只取文本，截断由调用方负责
        assertThat(f.get().message()).contains("Input string was not in a correct format");
    }

    @Test
    void 样本_1_2_Sender_Fault_判为客户端类_且按报文自身命名空间定版本() throws Exception {
        Optional<SoapFaultParser.Fault> f = SoapFaultParser.parse(fixture("fault/dneonline-sender-12.resp.xml"));
        assertThat(f).isPresent();
        assertThat(f.get().soapVersion()).isEqualTo("1.2");       // Code/Reason 结构
        assertThat(f.get().normalizedCode()).isEqualTo("Sender");
        assertThat(f.get().clientSide()).isTrue();
    }

    @Test
    void 样本_VersionMismatch_判为客户端类_且版本看报文而非配置() throws Exception {
        // 事实：向 1.1-only 服务发 1.2 报文，服务端用【1.1 结构】回 VersionMismatch（样本集 C-2）
        Optional<SoapFaultParser.Fault> f = SoapFaultParser.parse(
                fixture("fault/hello-versionmismatch-11only.resp.xml"));
        assertThat(f).isPresent();
        assertThat(f.get().soapVersion()).isEqualTo("1.1");        // ← 关键：报文是 1.1 结构
        assertThat(f.get().normalizedCode()).isEqualTo("VersionMismatch");
        assertThat(f.get().clientSide()).isTrue();                 // ← 若不归客户端类会被无限补偿
        assertThat(f.get().message()).contains("SOAP 1.2 message is not valid");
    }

    // ---------- 真实样本：非 Fault（不得误判） ----------

    @Test
    void 样本_业务错误走200正常响应_非Fault() throws Exception {
        assertThat(SoapFaultParser.parse(fixture("success/w3schools-bizerror-200.resp.xml"))).isEmpty();
        assertThat(SoapFaultParser.parse(fixture("success/oorsprong-unknowncountry-200.resp.xml"))).isEmpty();
    }

    @Test
    void 样本_正常成功响应_非Fault() throws Exception {
        assertThat(SoapFaultParser.parse(fixture("success/dneonline-add-11.resp.xml"))).isEmpty();
        assertThat(SoapFaultParser.parse(fixture("success/mnb-rates-12.resp.xml"))).isEmpty();
    }

    // ---------- 边界：不是 SOAP 就不能误判为 Fault ----------

    @Test
    void 非SOAP的XML_即使含Fault字面量也返回空() {
        // 结构匹配（Envelope 命名空间 + Body/Fault）而非字符串嗅探 —— 这是设计明确点
        byte[] notSoap = "<request><Fault><faultcode>x</faultcode></Fault></request>"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(SoapFaultParser.parse(notSoap)).isEmpty();

        byte[] otherNs = ("<Envelope xmlns=\"http://example.com/ns\"><Body><Fault>"
                + "<faultcode>x</faultcode></Fault></Body></Envelope>").getBytes(StandardCharsets.UTF_8);
        assertThat(SoapFaultParser.parse(otherNs)).isEmpty();
    }

    @Test
    void 空体_垃圾字节_无Body_均返回空且不抛异常() {
        assertThat(SoapFaultParser.parse(null)).isEmpty();
        assertThat(SoapFaultParser.parse(new byte[0])).isEmpty();
        assertThat(SoapFaultParser.parse("not xml at all".getBytes(StandardCharsets.UTF_8))).isEmpty();
        byte[] envelopeNoBody = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"/>"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(SoapFaultParser.parse(envelopeNoBody)).isEmpty();
    }

    @Test
    void 前缀无语义_只看命名空间与localName() {
        // 样本里 1.2 的 Fault 用 soap: 前缀绑定 2003/05 命名空间 —— 前缀不能参与判断
        byte[] r = ("<x:Envelope xmlns:x=\"http://www.w3.org/2003/05/soap-envelope\"><x:Body><x:Fault>"
                + "<x:Code><x:Value>env:Receiver</x:Value></x:Code>"
                + "<x:Reason><x:Text>boom</x:Text></x:Reason></x:Fault></x:Body></x:Envelope>")
                .getBytes(StandardCharsets.UTF_8);
        Optional<SoapFaultParser.Fault> f = SoapFaultParser.parse(r);
        assertThat(f).isPresent();
        assertThat(f.get().soapVersion()).isEqualTo("1.2");
        assertThat(f.get().normalizedCode()).isEqualTo("Receiver");
        assertThat(f.get().clientSide()).isFalse();   // 服务端类 → 维持重试/补偿
    }

    @Test
    void 未识别的faultcode_保守地当作服务端类() {
        // 宁可重试，不可丢单：只有 4 个已知客户端码才走死信
        byte[] r = ("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                + "<soap:Fault><faultcode>some:Weird</faultcode><faultstring>x</faultstring>"
                + "</soap:Fault></soap:Body></soap:Envelope>").getBytes(StandardCharsets.UTF_8);
        Optional<SoapFaultParser.Fault> f = SoapFaultParser.parse(r);
        assertThat(f).isPresent();
        assertThat(f.get().clientSide()).isFalse();
        assertThat(f.get().normalizedCode()).isEqualTo("Weird");
    }
}
