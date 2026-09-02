package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.AdapterDtos.ImplField;
import com.deepx.apicenter.dto.AdapterDtos.ImplMeta;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 适配器 impl 元数据清单（M1 硬编码，M2 与 Bean 注册打通）：
 * 每个 impl 声明参数 schema（照原型 ADAPTER_FIELDS 模式），管理面据此动态渲染表单、
 * 后端据此校验 params。field.kind 取值：
 * text 文本 / select 下拉（options）/ number 数字 / switch 开关 / secret 凭证（不落 params）/
 * codeMap 错误码映射 / textarea 多行文本。
 */
@Component
public class AdapterImplCatalog {

    private static final List<ImplMeta> IMPLS = List.of(
            // ---------- 鉴权 auth（首期 6 种 + Noop，M0-01 §8） ----------
            new ImplMeta("NoopAuthAdapter", "auth", "无鉴权", List.of()),
            new ImplMeta("ApiKeyAuthAdapter", "auth", "API Key", List.of(
                    f("headerName", "密钥 Header 名", "select", true, List.of("X-API-Key", "X-App-Id", "X-Auth-Token", "api-key")),
                    f("apiKey", "API Key", "secret", false, null))),
            new ImplMeta("HmacAuthAdapter", "auth", "HMAC 签名", List.of(
                    f("signatureAlgorithm", "签名算法", "select", true, List.of("HMAC-SHA256", "HMAC-SHA1", "HMAC-SHA512")),
                    f("signatureHeader", "签名 Header 名", "select", true, List.of("X-Signature", "X-Hub-Signature-256")),
                    f("timestampToleranceSeconds", "时间戳容差(秒)", "number", true, List.of()),
                    f("replayProtection", "防重放", "switch", false, null))),
            new ImplMeta("BearerTokenAuthAdapter", "auth", "Bearer Token", List.of(
                    f("token", "Token", "secret", false, null),
                    f("headerName", "Token Header 名", "select", true, List.of("Authorization", "X-Auth-Token", "X-Access-Token")),
                    f("prefix", "前缀", "select", true, List.of("Bearer", "Token")))),
            new ImplMeta("CloudSignatureAdapter", "auth", "云厂商签名", List.of(
                    f("scheme", "签名规范", "select", true, List.of("TC3-HMAC-SHA256", "AWS4-HMAC-SHA256", "ACS3-HMAC-SHA256")),
                    f("secretId", "SecretId", "secret", false, null),
                    f("secretKey", "SecretKey", "secret", false, null),
                    f("service", "服务名", "text", true, null),
                    f("region", "地域", "text", false, null),
                    f("signedHeaders", "签名 Header 列表", "text", false, null))),
            new ImplMeta("CloudCallbackSignatureAdapter", "auth", "云厂商回调验签", List.of(
                    f("scheme", "回调验签规范", "select", true, List.of("TENCENT-EVENT", "AWS-SNS", "ALIYUN-CALLBACK")),
                    f("token", "回调 Token", "secret", false, null),
                    f("certificate", "验签证书", "text", false, null))),
            // ---------- 协议 protocol ----------
            new ImplMeta("JsonProtocolAdapter", "protocol", "JSON 编解码", List.of(
                    f("namingStrategy", "命名策略", "select", true, List.of("CAMEL_CASE", "SNAKE_CASE", "KEBAB_CASE")),
                    f("dateFormat", "日期格式", "select", false, List.of("ISO", "yyyy-MM-dd HH:mm:ss")),
                    f("ignoreUnknown", "忽略未知字段", "switch", false, null),
                    f("nullHandling", "空值策略", "select", false, List.of("OMIT", "INCLUDE", "AS_NULL")),
                    f("numberPrecision", "数字精度", "select", false, List.of("DOUBLE", "BIG_DECIMAL", "STRING")))),
            new ImplMeta("XmlProtocolAdapter", "protocol", "XML 编解码", List.of(
                    f("rootElement", "根元素", "text", true, null),
                    f("namespace", "命名空间", "text", false, null),
                    f("attrVsElement", "属性映射方式", "select", true, List.of("ELEMENT", "ATTRIBUTE")))),
            // ---------- 报文 message ----------
            new ImplMeta("NoopMessageAdapter", "message", "直通（无转换）", List.of()),
            new ImplMeta("EnvelopeMessageAdapter", "message", "信封报文适配", List.of(
                    f("envelope", "业务数据容器", "text", true, null),
                    f("codeField", "上游状态码字段", "text", true, null),
                    f("successValue", "成功值", "text", true, null),
                    f("codeMappings", "错误码映射", "codeMap", false, null),
                    f("messageField", "消息字段", "text", false, null),
                    f("defaultErrorCode", "兜底错误码", "text", false, null))),
            new ImplMeta("HeaderMappingAdapter", "message", "报文头映射", List.of(
                    f("headerMappings", "报文头映射规则", "textarea", true, null)))
    );

    private static ImplField f(String key, String label, String kind, boolean required, List<String> options) {
        return new ImplField(key, label, kind, required, options);
    }

    public List<ImplMeta> all() {
        return IMPLS;
    }

    public Optional<ImplMeta> byImpl(String impl) {
        return IMPLS.stream().filter(m -> m.impl().equals(impl)).findFirst();
    }
}
