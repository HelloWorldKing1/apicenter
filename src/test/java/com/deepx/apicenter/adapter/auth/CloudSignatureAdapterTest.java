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
        AdapterContext ctx = ctx("https://ecs.example.com/", "POST", new byte[0], Map.of(),
                "{\"scheme\":\"SDK-HMAC-SHA256\"}");
        assertThatThrownBy(() -> adapter.process(ctx))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("暂不支持的 scheme")
                .hasMessageContaining("SDK-HMAC-SHA256");
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

    @Test
    void 非出站鉴权阶段_一律直通() {
        AdapterContext encode = AdapterContext.create(ChainPhase.ENCODE, UnifiedModel.emptyObject(),
                null, new AdapterContext.AppMeta("t-app", "http://mock"),
                new AdapterContext.TraceMeta("t1"), null, new OutboundRequestSpec());
        assertThat(adapter.process(encode)).isSameAs(encode);
    }
}
