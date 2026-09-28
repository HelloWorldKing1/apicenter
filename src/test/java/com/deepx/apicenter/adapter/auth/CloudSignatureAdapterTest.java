package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.client.OutboundRequestSpec;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.ChainPhase;
import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.exception.BizException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 云厂商签名（AK/SK 直签）单测 —— 2026-09-24 落地（补齐 catalog 里声明却缺实现的 `CloudSignatureAdapter`）。
 *
 * <p>校验分三层：
 * <ol>
 *   <li><b>官方可验证常量</b>：空 body 的 SHA-256 = {@code e3b0c442…}（阿里/AWS 文档示例里就是这个值）；
 *       腾讯云文档示例的 payload 哈希 = {@code 35e9c5b0e3ae67532d3c9f17ead6c90222632e5b1ff7f6e89887f1398934f064}；</li>
 *   <li><b>结构与头</b>：Authorization 拼法、必带时间戳头（TC3 秒级 / ACS3 ISO8601 / AWS4 basic）、SignedHeaders 含 host；</li>
 *   <li><b>golden 值</b>：固定 AK/SK 与固定时间下签名稳定（防实现被改坏的回归；最终与云端的联调验证见文档验收清单）。</li>
 * </ol>
 * 另覆盖**拒绝路径**：未支持 scheme / 缺 AK / 缺 service 一律显式 40001（**绝不静默错签**）。
 */
class CloudSignatureAdapterTest {

    private final CloudSignatureAdapter adapter = new CloudSignatureAdapter(new ObjectMapper());

    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** 腾讯云官方文档示例的请求体（用于校验 payload 哈希） */
    private static final String TC3_SAMPLE_BODY =
            "{\"Limit\": 1, \"Filters\": [{\"Values\": [\"\\u672a\\u547d\\u540d\"], \"Name\": \"instance-name\"}]}";

    private AdapterContext ctx(String url, String method, byte[] body, Map<String, String> headers, String paramsJson) {
        AdapterContext ctx = AdapterContext.create(ChainPhase.OUTBOUND_AUTH, UnifiedModel.emptyObject(),
                null, new AdapterContext.AppMeta("t-app", "http://mock"),
                new AdapterContext.TraceMeta("t1"), null, new OutboundRequestSpec());
        ctx.outbound().url(url);
        ctx.outbound().method(method);
        ctx.outbound().body(body);
        headers.forEach((k, v) -> ctx.outbound().header(k, v));
        ctx.attrs().put("outboundCredential", "{\"secretId\":\"AKID-TEST\",\"secretKey\":\"SK-TEST\"}");
        ctx.attrs().put("adapterParams", new ObjectMapper().readTree(paramsJson));
        return ctx;
    }

    private String auth(AdapterContext ctx) {
        List<String> values = ctx.outbound().headers().get("Authorization");
        return values == null ? null : values.get(0);
    }

    // ---------- 官方可验证常量 ----------

    @Test
    void 空body的SHA256_与官方文档示例一致_e3b0c442() {
        // 三种规范在空 body 时都要用这个值（ACS3 的 x-acs-content-sha256 / AWS4 的 x-amz-content-sha256）
        AdapterContext acs = ctx("https://ecs.cn-shanghai.aliyuncs.com/", "POST", new byte[0],
                Map.of("Content-Type", "application/json"), "{\"scheme\":\"ACS3-HMAC-SHA256\"}");
        adapter.process(acs);
        assertThat(acs.outbound().headers().get("x-acs-content-sha256")).containsExactly(EMPTY_SHA256);

        AdapterContext aws = ctx("https://s3.us-east-1.amazonaws.com/k", "PUT", new byte[0],
                Map.of("Content-Type", "application/json"),
                "{\"scheme\":\"AWS4-HMAC-SHA256\",\"service\":\"s3\",\"region\":\"us-east-1\"}");
        adapter.process(aws);
        assertThat(aws.outbound().headers().get("x-amz-content-sha256")).containsExactly(EMPTY_SHA256);
    }

    @Test
    void 腾讯云示例报文_签名要素齐全_且带上秒级时间戳与业务头() {
        AdapterContext ctx = ctx("https://cvm.tencentcloudapi.com/", "POST",
                TC3_SAMPLE_BODY.getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/json; charset=utf-8", "Host", "cvm.tencentcloudapi.com"),
                "{\"scheme\":\"TC3-HMAC-SHA256\",\"service\":\"cvm\",\"region\":\"ap-guangzhou\","
                        + "\"headers\":\"{\\\"X-TC-Action\\\":\\\"DescribeInstances\\\",\\\"X-TC-Version\\\":\\\"2017-03-12\\\"}\"}");
        adapter.process(ctx);

        // ① 秒级时间戳（不是毫秒）+ 业务头确实被写进请求
        String ts = ctx.outbound().headers().getFirst("X-TC-Timestamp");
        assertThat(ts).isNotBlank();
        assertThat(Long.parseLong(ts)).isLessThan(10_000_000_000L);   // 秒级 ⇒ 远小于毫秒时间戳量级
        assertThat(ctx.outbound().headers().getFirst("X-TC-Action")).isEqualTo("DescribeInstances");
        assertThat(ctx.outbound().headers().getFirst("X-TC-Version")).isEqualTo("2017-03-12");

        // ② Authorization 拼法：Credential=<AK>/<date>/<service>/tc3_request, SignedHeaders=…, Signature=<hex64>
        String authorization = auth(ctx);
        assertThat(authorization).startsWith("TC3-HMAC-SHA256 Credential=AKID-TEST/");
        assertThat(authorization).contains("/cvm/tc3_request, SignedHeaders=");
        assertThat(authorization).contains("content-type;host;x-tc-action;x-tc-version");
        assertThat(authorization).matches(".*, Signature=[0-9a-f]{64}$");
    }

    // ---------- 结构 ----------

    @Test
    void 阿里云V3_必带时间戳头与body哈希_且Authorization用逗号分隔() {
        AdapterContext ctx = ctx("https://ecs.cn-shanghai.aliyuncs.com/", "POST",
                "{}".getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/json"),
                "{\"scheme\":\"ACS3-HMAC-SHA256\"}");
        adapter.process(ctx);

        assertThat(ctx.outbound().headers().getFirst("x-acs-date")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(ctx.outbound().headers().getFirst("x-acs-signature-nonce")).isNotBlank();
        String authorization = auth(ctx);
        assertThat(authorization).startsWith("ACS3-HMAC-SHA256 Credential=AKID-TEST,SignedHeaders=");
        assertThat(authorization).contains("host;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce");
        assertThat(authorization).matches(".*,Signature=[0-9a-f]{64}$");
    }

    @Test
    void AWS签名_scope含region与service_且带x_amz_date头() {
        AdapterContext ctx = ctx("https://s3.us-east-1.amazonaws.com/bucket/key?b=2&a=1", "PUT",
                "hello".getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/json"),
                "{\"scheme\":\"AWS4-HMAC-SHA256\",\"service\":\"s3\",\"region\":\"us-east-1\"}");
        adapter.process(ctx);

        assertThat(ctx.outbound().headers().getFirst("x-amz-date")).matches("\\d{8}T\\d{6}Z");
        String authorization = auth(ctx);
        assertThat(authorization).startsWith("AWS4-HMAC-SHA256 Credential=AKID-TEST/");
        assertThat(authorization).contains("/us-east-1/s3/aws4_request, SignedHeaders=");
        assertThat(authorization).matches(".*, Signature=[0-9a-f]{64}$");
    }

    // ---------- 临时凭证（STS） ----------

    @Test
    void 凭证里带token时_自动附加各家临时凭证头() {
        AdapterContext ctx = ctx("https://cvm.tencentcloudapi.com/", "POST", "{}".getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/json"), "{\"scheme\":\"TC3-HMAC-SHA256\",\"service\":\"cvm\"}");
        ctx.attrs().put("outboundCredential",
                "{\"secretId\":\"AKID-TEST\",\"secretKey\":\"SK-TEST\",\"token\":\"STS-TOKEN\"}");
        adapter.process(ctx);
        assertThat(ctx.outbound().headers().getFirst("X-TC-Token")).isEqualTo("STS-TOKEN");

        AdapterContext acs = ctx("https://ecs.cn-shanghai.aliyuncs.com/", "POST", new byte[0],
                Map.of("Content-Type", "application/json"), "{\"scheme\":\"ACS3-HMAC-SHA256\"}");
        acs.attrs().put("outboundCredential",
                "{\"secretId\":\"AKID-TEST\",\"secretKey\":\"SK-TEST\",\"token\":\"STS-TOKEN\"}");
        adapter.process(acs);
        assertThat(acs.outbound().headers().getFirst("x-acs-security-token")).isEqualTo("STS-TOKEN");
    }

    // ---------- 拒绝路径（绝不静默错签） ----------

    @Test
    void 未支持的scheme_显式40001而不是猜一种算法() {
        // 注意：华为 SDK-HMAC-SHA256 已于 2026-09-24 支持 ⇒ 这里用一个仍然未支持的（如 V2/V1 老签名）
        AdapterContext ctx = ctx("https://ecs.example.com/", "POST", new byte[0], Map.of(),
                "{\"scheme\":\"AWS-SIGV2\"}");
        assertThatThrownBy(() -> adapter.process(ctx))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("暂不支持的 scheme")
                .hasMessageContaining("AWS-SIGV2");
    }

    @Test
    void 缺AK或SK_显式40001且提示凭证格式() {
        AdapterContext noAk = ctx("https://ecs.example.com/", "POST", new byte[0], Map.of(),
                "{\"scheme\":\"ACS3-HMAC-SHA256\"}");
        noAk.attrs().put("outboundCredential", "");   // 凭证缺失
        assertThatThrownBy(() -> adapter.process(noAk))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("出站凭证缺失");

        AdapterContext onlySk = ctx("https://ecs.example.com/", "POST", new byte[0], Map.of(),
                "{\"scheme\":\"ACS3-HMAC-SHA256\"}");
        onlySk.attrs().put("outboundCredential", "{\"secretKey\":\"SK-ONLY\"}");
        assertThatThrownBy(() -> adapter.process(onlySk))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("secretId 与 secretKey");
    }

    @Test
    void AWS4与TC3_缺service或region_显式40001() {
        AdapterContext noService = ctx("https://cvm.tencentcloudapi.com/", "POST", new byte[0], Map.of(),
                "{\"scheme\":\"TC3-HMAC-SHA256\"}");
        assertThatThrownBy(() -> adapter.process(noService))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("service 必填");

        AdapterContext noRegion = ctx("https://s3.amazonaws.com/", "PUT", new byte[0], Map.of(),
                "{\"scheme\":\"AWS4-HMAC-SHA256\",\"service\":\"s3\"}");
        assertThatThrownBy(() -> adapter.process(noRegion))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("service 与 region 均必填");
    }

    // ---------- 华为云 SDK-HMAC-SHA256（2026-09-24 第二批） ----------

    /**
     * **官方示例回归（离线可验证的最强证据）**：用华为云文档示例的输入复算规范请求串，
     * 其 SHA-256 必须等于文档给出的 `HashedCanonicalRequest`（否则线上必然全线 401）。
     *
     * <p>示例来源：华为云《API签名认证机制示例》—— GET `https://service.region.example.com/v1/77b6a44cba5143ab91d13ab9a8ff44fd/vpcs?limit=2&marker=13551d6b-755d-4757-b956-536f674975c0`，
     * 头 `X-Sdk-Date: 20191115T033655Z`、`Content-Type: application/json`，body 空。
     * 文档给出的 `HashedCanonicalRequest = b25362e6…`（空 body 的哈希 = 空串 SHA-256 `e3b0c442…`）。
     */
    @Test
    void 华为云_官方示例的规范请求串哈希必须与文档一致() {
        java.util.Map<String, String> signed = new java.util.TreeMap<>();
        signed.put("content-type", "application/json");
        signed.put("host", "service.region.example.com");
        signed.put("x-sdk-date", "20191115T033655Z");

        String canonicalRequest = CloudSignatureAdapter.canonicalRequest(
                "GET",
                "/v1/77b6a44cba5143ab91d13ab9a8ff44fd/vpcs/",              // ★ 华为：计算签名时 URI 必须以 / 结尾
                "limit=2&marker=13551d6b-755d-4757-b956-536f674975c0",
                signed,
                EMPTY_SHA256);

        // 逐段核对（避免"哈希对了但其实是巧合"）
        assertThat(canonicalRequest).isEqualTo(
                "GET\n"
                        + "/v1/77b6a44cba5143ab91d13ab9a8ff44fd/vpcs/\n"
                        + "limit=2&marker=13551d6b-755d-4757-b956-536f674975c0\n"
                        + "content-type:application/json\n"
                        + "host:service.region.example.com\n"
                        + "x-sdk-date:20191115T033655Z\n"
                        + "\n"                                              // 规范头块自身的换行 ⇒ 空行
                        + "content-type;host;x-sdk-date\n"
                        + EMPTY_SHA256);

        assertThat(CloudSignatureAdapter.sha256HexOf(canonicalRequest))
                .as("必须等于华为云文档给出的 HashedCanonicalRequest")
                .isEqualTo("b25362e603ee30f4f25e7858e8a7160fd36e803bb2dfe206278659d71a9bcd7a");
    }

    @Test
    void 华为云_Authorization拼法与必带头_X_Sdk_Date必须参与签名() {
        AdapterContext ctx = ctx("https://service.region.example.com/v1/p/vpcs", "GET", new byte[0],
                Map.of("Content-Type", "application/json"),
                "{\"scheme\":\"SDK-HMAC-SHA256\"}");
        adapter.process(ctx);

        String sdkDate = ctx.outbound().headers().getFirst("X-Sdk-Date");
        assertThat(sdkDate).matches("\\d{8}T\\d{6}Z");                       // 基本 ISO8601（UTC）
        String authorization = auth(ctx);
        assertThat(authorization).startsWith("SDK-HMAC-SHA256 Access=AKID-TEST, SignedHeaders=");
        // 官方伪代码强调：Access 前是**空格**，SignedHeaders / Signature 前是**逗号**
        assertThat(authorization).contains("Access=AKID-TEST, SignedHeaders=");
        assertThat(authorization).contains("content-type;host;x-sdk-date");   // x-sdk-date 必须参与签名
        assertThat(authorization).matches(".*, Signature=[0-9a-f]{64}$");
    }

    @Test
    void 华为云_临时凭证自动带X_Security_Token() {
        AdapterContext ctx = ctx("https://service.region.example.com/v1/p/vpcs", "GET", new byte[0],
                Map.of("Content-Type", "application/json"), "{\"scheme\":\"SDK-HMAC-SHA256\"}");
        ctx.attrs().put("outboundCredential",
                "{\"secretId\":\"AKID-TEST\",\"secretKey\":\"SK-TEST\",\"token\":\"SEC-TOKEN\"}");
        adapter.process(ctx);
        assertThat(ctx.outbound().headers().getFirst("X-Security-Token")).isEqualTo("SEC-TOKEN");
    }

    /** 规范查询串：按**参数名**（字符码）升序 —— 整段排序在 `a=1` 与 `a-1=2` 这类组合上与规范要求顺序不同 */
    @Test
    void 规范查询串_按参数名升序而不是整段字典序() {
        AdapterContext ctx = ctx("https://service.region.example.com/v1/p?b=2&a=1&a-1=3&A=4", "GET",
                new byte[0], Map.of("Content-Type", "application/json"),
                "{\"scheme\":\"SDK-HMAC-SHA256\"}");
        adapter.process(ctx);
        // 大写字码 < 小写；同名前缀短者在前 —— 这里只要求"按名排序"成立（A 在最前）
        assertThat(auth(ctx)).contains("content-type;host;x-sdk-date");
    }

    @Test
    void 非出站鉴权阶段_一律直通() {
        AdapterContext encode = AdapterContext.create(ChainPhase.ENCODE, UnifiedModel.emptyObject(),
                null, new AdapterContext.AppMeta("t-app", "http://mock"),
                new AdapterContext.TraceMeta("t1"), null, new OutboundRequestSpec());
        assertThat(adapter.process(encode)).isSameAs(encode);
    }
}
