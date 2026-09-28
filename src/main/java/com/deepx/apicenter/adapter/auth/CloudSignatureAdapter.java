package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.AdapterType;
import com.deepx.apicenter.engine.ChainPhase;
import com.deepx.apicenter.exception.BizException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * **云厂商签名（AK/SK 直签）** 出站鉴权适配器 —— 2026-09-24 落地（补齐 `AdapterImplCatalog` 里早已声明、
 * 但实现类缺失的 `CloudSignatureAdapter`；缺失时 `ChainEngine#bean` 会 fail-loud 报 40001「适配器实现未注册」）。
 *
 * <h3>为什么是「直签」而不是「换 Token」</h3>
 * 云厂商有两类用法：① **静态 AK/SK 每请求签名**（本类；Token 就是签名本身，无状态、无过期）
 * ② **AK/SK 换临时凭证**（STS / OAuth2：先调换发接口拿 Token 再带 `X-TC-Token`/`SecurityToken` 之类）——
 * 后者**不要**用本类实现（它需要"先换 token + 缓存 + 到期刷新"），应由**前置接口编排**的「令牌步骤」承担
 * （分析见《开发记录/AK-SK与临时令牌鉴权适配分析.md》方案 B）。两者**在界面上是可选项**：供应商有换发接口就用编排，
 * 只给 AK/SK 就用本类。
 *
 * <h3>支持的签名规范（与 catalog 声明的三种一致）</h3>
 * <ul>
 *   <li>{@code TC3-HMAC-SHA256}（腾讯云）：CanonicalRequest 六段 + `Authorization: TC3-HMAC-SHA256 Credential=…, SignedHeaders=…, Signature=…`，
 *       必带 `X-TC-Timestamp`（**秒**）与 `Content-Type`；临时凭证由凭证 JSON 的 {@code token} 字段自动带 `X-TC-Token`；</li>
 *   <li>{@code ACS3-HMAC-SHA256}（阿里云 V3）：同形六段 + `Authorization: ACS3-HMAC-SHA256 Credential=<AK>,SignedHeaders=…,Signature=…`，
 *       必带 `x-acs-date`（ISO8601 UTC）与 `x-acs-content-sha256`（body 哈希）；</li>
 *   <li>{@code AWS4-HMAC-SHA256}（AWS SigV4）：同形六段 + `Authorization: AWS4-HMAC-SHA256 Credential=<AK>/<date>/<region>/<service>/aws4_request, …`，
 *       必带 `x-amz-date`（`yyyyMMdd'T'HHmmss'Z'`）与 `x-amz-content-sha256`；临时凭证带 `x-amz-security-token`。</li>
 * </ul>
 * 三者的**签名密钥派生不同**（见 {@link #signingKey}），这是最容易写错的地方 —— 已按各自文档实现并回归。
 *
 * <h3>凭证形态（M0-04 复合凭证）</h3>
 * 应用凭证值（`app_credential`，kind=`OUTBOUND`）为 **JSON**：{@code {"secretId":"…","secretKey":"…","token":"…"(可选,临时凭证)}}。
 * 也兼容"单字段裸值"（只有 SecretKey 时按 {@code {"secretKey": <裸值>}} 处理），但**缺 SecretId 一律拒绝**（AK 是签名标识，瞎猜只会全 401）。
 *
 * <h3>不做什么（避免静默错签）</h3>
 * 未知 / 未支持的 scheme（如华为 `SDK-HMAC-SHA256`）**显式 40001 拒绝**，绝不"猜一种算法签上去"——
 * 签名错误在供应商侧表现为 401/403，排查成本极高。
 */
@Component("CloudSignatureAdapter")
public class CloudSignatureAdapter implements Adapter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CloudSignatureAdapter.class);

    private static final DateTimeFormatter ISO_BASIC_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO_EXTENDED_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;

    public CloudSignatureAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public AdapterType type() {
        return AdapterType.AUTH;
    }

    @Override
    public AdapterContext process(AdapterContext ctx) {
        if (ctx.phase() != ChainPhase.OUTBOUND_AUTH) {
            return ctx;   // 仅出站鉴权阶段生效（与既有 auth 适配器同口径）
        }
        JsonNode params = (JsonNode) ctx.attrs().get("adapterParams");
        String scheme = text(params, "scheme", "");
        if (scheme.isBlank()) {
            throw BizException.fieldInvalid("云厂商签名：未配置 scheme（可选 TC3-HMAC-SHA256 / ACS3-HMAC-SHA256 / AWS4-HMAC-SHA256）");
        }
        Cred cred = credential(ctx, params);
        applyConfiguredHeaders(ctx, params);      // 业务头（如 TC3 的 X-TC-Action/X-TC-Version）
        String service = text(params, "service", "");
        String region = text(params, "region", "");
        String extraSigned = text(params, "signedHeaders", "");
        List<String> signNames = signedNames(params, extraSigned);   // 显式列出的 + headers 里配的业务头

        switch (scheme.trim().toUpperCase(Locale.ROOT)) {
            case "TC3-HMAC-SHA256" -> signTc3(ctx, cred, service, signNames);
            case "ACS3-HMAC-SHA256" -> signAcs3(ctx, cred, signNames);
            case "AWS4-HMAC-SHA256" -> signAws4(ctx, cred, service, region, signNames);
            default -> throw BizException.fieldInvalid(
                    "云厂商签名：暂不支持的 scheme = " + scheme
                            + "（当前支持 TC3-HMAC-SHA256 / ACS3-HMAC-SHA256 / AWS4-HMAC-SHA256；"
                            + "华为 SDK-HMAC-SHA256 等列为下一批（避免「猜算法」导致全线 401）");
        }
        return ctx;
    }

    // ---------- 三种规范 ----------

    /** 腾讯云 TC3-HMAC-SHA256：密钥派生 date → service → tc3_request */
    private void signTc3(AdapterContext ctx, Cred cred, String service, List<String> signNames) {
        if (service.isBlank()) {
            throw BizException.fieldInvalid("云厂商签名（TC3）：service 必填（如 cvm / sts）");
        }
        long ts = Instant.now().getEpochSecond();                 // ⚠️ 秒（不是毫秒）
        String timestamp = String.valueOf(ts);
        String date = isoBasicUtc(ts).substring(0, 8);            // yyyyMMdd

        Map<String, String> signed = new TreeMap<>();
        signed.put("content-type", header(ctx, "Content-Type", "application/json"));
        signed.put("host", hostOf(ctx));
        for (String name : signNames) {
            signed.put(name.toLowerCase(Locale.ROOT), header(ctx, name, ""));
        }
        String payloadHash = sha256Hex(body(ctx));

        String canonicalRequest = canonicalRequest(ctx, signed, payloadHash);
        String credentialScope = date + "/" + service + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        // 密钥派生（TC3）：kDate=HMAC("TC3"+sk, date) → kService=HMAC(kDate, service) → kSigning=HMAC(kService, "tc3_request")
        byte[] kDate = hmac(("TC3" + cred.secretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] kService = hmac(kDate, service);
        byte[] kSigning = hmac(kService, "tc3_request");
        String signature = hex(hmac(kSigning, stringToSign));

        ctx.outbound().header("X-TC-Timestamp", timestamp);
        applySignedHeaders(ctx, signed);
        if (cred.token() != null) {
            ctx.outbound().header("X-TC-Token", cred.token());   // 临时凭证
        }
        ctx.outbound().header("Authorization", "TC3-HMAC-SHA256 Credential=" + cred.secretId() + "/" + credentialScope
                + ", SignedHeaders=" + String.join(";", signed.keySet()) + ", Signature=" + signature);
        log.debug("TC3 签名完成：service={} date={} signedHeaders={}", service, date, signed.keySet());
    }

    /** 阿里云 V3（ACS3-HMAC-SHA256）：签名密钥直接用 SecretKey（**不拼 &**，这是与 POP/v2 的关键区别） */
    private void signAcs3(AdapterContext ctx, Cred cred, List<String> signNames) {
        String date = ISO_EXTENDED_UTC.format(Instant.now());      // 2023-10-26T09:01:01Z
        String payloadHash = sha256Hex(body(ctx));
        Map<String, String> signed = new TreeMap<>();
        signed.put("host", hostOf(ctx));
        signed.put("x-acs-date", date);
        signed.put("x-acs-content-sha256", payloadHash);
        signed.put("x-acs-signature-nonce", Long.toHexString(System.nanoTime()));
        for (String name : signNames) {
            signed.put(name.toLowerCase(Locale.ROOT), header(ctx, name, ""));
        }
        // ⚠️ 三家的 CanonicalRequest **第 6 段都是 body 哈希**（x-acs-content-sha256 只是"也作为已签名头"）
        String canonicalRequest = canonicalRequest(ctx, signed, payloadHash);
        String stringToSign = "ACS3-HMAC-SHA256\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = hex(hmac(cred.secretKey().getBytes(StandardCharsets.UTF_8), stringToSign));

        applySignedHeaders(ctx, signed);
        if (cred.token() != null) {
            ctx.outbound().header("x-acs-security-token", cred.token());   // 临时凭证（STS）
        }
        ctx.outbound().header("Authorization", "ACS3-HMAC-SHA256 Credential=" + cred.secretId()
                + ",SignedHeaders=" + String.join(";", signed.keySet()) + ",Signature=" + signature);
    }

    /** AWS SigV4：密钥派生 date → region → service → aws4_request */
    private void signAws4(AdapterContext ctx, Cred cred, String service, String region, List<String> signNames) {
        if (service.isBlank() || region.isBlank()) {
            throw BizException.fieldInvalid("云厂商签名（AWS4）：service 与 region 均必填（如 s3 / us-east-1）");
        }
        long now = Instant.now().getEpochSecond();
        String dateTime = isoBasicUtc(now);                        // 20130524T000000Z
        String date = dateTime.substring(0, 8);

        String payloadHash = sha256Hex(body(ctx));
        Map<String, String> signed = new TreeMap<>();
        signed.put("host", hostOf(ctx));
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", dateTime);
        for (String name : signNames) {
            signed.put(name.toLowerCase(Locale.ROOT), header(ctx, name, ""));
        }
        // ⚠️ 同上：第 6 段是 body 哈希（x-amz-content-sha256 只是"也作为已签名头"）
        String canonicalRequest = canonicalRequest(ctx, signed, payloadHash);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + dateTime + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        // 密钥派生（AWS4）：kDate → kRegion → kService → kSigning
        byte[] kDate = hmac(("AWS4" + cred.secretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));

        applySignedHeaders(ctx, signed);
        String auth = "AWS4-HMAC-SHA256 Credential=" + cred.secretId() + "/" + scope
                + ", SignedHeaders=" + String.join(";", signed.keySet()) + ", Signature=" + signature;
        if (cred.token() != null) {
            ctx.outbound().header("x-amz-security-token", cred.token());
        }
        ctx.outbound().header("Authorization", auth);
    }

    // ---------- 规范请求串 ----------

    /**
     * 六段规范请求串（TC3 / ACS3 / AWS4 同形）：
     * {@code Method \n CanonicalURI \n CanonicalQueryString \n CanonicalHeaders \n SignedHeaders \n HashedRequestPayload}。
     *
     * @param payloadHash 显式第 6 段（TC3 用）；传 {@code null} 表示"哈希已作为已签名头承载"（ACS3/AWS4）
     */
    private String canonicalRequest(AdapterContext ctx, Map<String, String> signedHeaders, String payloadHash) {
        StringBuilder canonicalHeaders = new StringBuilder();
        for (Map.Entry<String, String> e : signedHeaders.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':').append(e.getValue().trim()).append('\n');
        }
        String signedNames = String.join(";", signedHeaders.keySet());
        String sixth = payloadHash != null ? payloadHash : "";
        return ctx.outbound().method().toUpperCase(Locale.ROOT) + "\n"
                + canonicalUri(ctx) + "\n"
                + canonicalQuery(ctx) + "\n"
                + canonicalHeaders + "\n"
                + signedNames + "\n"
                + sixth;
    }

    /** 规范化 URI：路径原样（云厂商均要求"已编码的路径"，我们不做二次编码，避免把 %2F 变 %252F） */
    private String canonicalUri(AdapterContext ctx) {
        String path = URI.create(ctx.outbound().url()).getRawPath();
        return path == null || path.isBlank() ? "/" : path;
    }

    /** 规范化查询串：按 key 排序后 `a=1&b=2`（空则空串 ✓ 三家都要求 POST 场景为空串） */
    private String canonicalQuery(AdapterContext ctx) {
        String query = URI.create(ctx.outbound().url()).getRawQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>(List.of(query.split("&")));
        parts.sort(String::compareTo);
        return String.join("&", parts);
    }

    /** 把参与签名的头（除 Authorization 自身）落回真实请求（`host` 由 HTTP 客户端自动带，不重复加） */
    private void applySignedHeaders(AdapterContext ctx, Map<String, String> signed) {
        for (Map.Entry<String, String> e : signed.entrySet()) {
            if ("host".equals(e.getKey())) {
                continue;
            }
            ctx.outbound().header(e.getKey(), e.getValue());
        }
    }

    // ---------- 凭证与工具 ----------

    /** 云厂商 AK/SK（可选临时凭证 token） */
    private record Cred(String secretId, String secretKey, String token) {
    }

    /**
     * 解析凭证：优先按 JSON 复合凭证（`{"secretId":…,"secretKey":…,"token":…}`），
     * 其次兼容"单字段裸值"（视作 secretKey，但**缺 secretId 直接拒绝**）。
     */
    private Cred credential(AdapterContext ctx, JsonNode params) {
        Object raw = ctx.attrs().get("outboundCredential");
        String value = raw == null ? "" : raw.toString().trim();
        if (value.isEmpty()) {
            throw BizException.fieldInvalid("云厂商签名：出站凭证缺失（请在「应用管理 → 凭证」按 JSON 填 {\"secretId\":…,\"secretKey\":…}）");
        }
        String ak = text(params, "secretId", "");
        String sk = text(params, "secretKey", "");
        String token = null;
        if (value.startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(value);
                if (ak.isBlank() && node.hasNonNull("secretId")) {
                    ak = node.get("secretId").asText();
                }
                if (sk.isBlank() && node.hasNonNull("secretKey")) {
                    sk = node.get("secretKey").asText();
                }
                if (node.hasNonNull("token")) {
                    token = node.get("token").asText();
                }
            } catch (Exception e) {
                throw BizException.fieldInvalid("云厂商签名：凭证 JSON 解析失败（期望 {\"secretId\":…,\"secretKey\":…}）");
            }
        } else if (sk.isBlank()) {
            sk = value;
        }
        if (ak.isBlank() || sk.isBlank()) {
            throw BizException.fieldInvalid("云厂商签名：需要同时提供 secretId 与 secretKey（AK 是签名标识，缺失会必然 401）");
        }
        return new Cred(ak, sk, token);
    }

    private String hostOf(AdapterContext ctx) {
        String host = URI.create(ctx.outbound().url()).getHost();
        if (host == null || host.isBlank()) {
            throw BizException.fieldInvalid("云厂商签名：无法从出站 URL 取到 host：" + ctx.outbound().url());
        }
        int port = URI.create(ctx.outbound().url()).getPort();
        return port > 0 ? host + ":" + port : host;
    }

    private String header(AdapterContext ctx, String name, String def) {
        for (Map.Entry<String, List<String>> e : ctx.outbound().headers().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return def;
    }

    private byte[] body(AdapterContext ctx) {
        return ctx.outbound().body() == null ? new byte[0] : ctx.outbound().body();
    }

    private static String text(JsonNode params, String key, String def) {
        if (params == null || !params.has(key) || params.get(key).isNull()) {
            return def;
        }
        return params.get(key).asText().trim();
    }

    /** 业务头（JSON 对象或 `k:v,k2:v2`）：如 TC3 的 `X-TC-Action` / `X-TC-Version`（云 API 3.0 放在头上） */
    private void applyConfiguredHeaders(AdapterContext ctx, JsonNode params) {
        String raw = text(params, "headers", "");
        if (raw.isBlank()) {
            return;
        }
        if (raw.startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(raw);
                node.properties().forEach(e -> ctx.outbound().header(e.getKey(), e.getValue().asText()));
                return;
            } catch (Exception e) {
                throw BizException.fieldInvalid("云厂商签名：headers 参数 JSON 非法（期望 {\"X-TC-Action\":\"DescribeInstances\"}）");
            }
        }
        for (String pair : raw.split(",")) {
            int idx = pair.indexOf(':');
            if (idx > 0) {
                ctx.outbound().header(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
            }
        }
    }

    /**
     * 参与签名的头名集合（除各家必选头外）：
     * `signedHeaders` 显式列出的 **＋** `headers` 参数里配置的业务头（腾讯云示例中 `x-tc-action` 就是参与签名的）。
     */
    private List<String> signedNames(JsonNode params, String extraSigned) {
        List<String> names = new ArrayList<>(splitNames(extraSigned));
        String raw = text(params, "headers", "");
        if (raw.startsWith("{")) {
            try {
                objectMapper.readTree(raw).properties().forEach(e -> names.add(e.getKey()));
            } catch (Exception ignored) {
                // applyConfiguredHeaders 已给出明确错误，这里不重复报
            }
        } else if (extraSigned.contains(";")) {
            names.addAll(splitNames(extraSigned));
        }
        return names;
    }

    private static List<String> splitNames(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : csv.split("[,;]")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String isoBasicUtc(long epochSeconds) {
        return ISO_BASIC_UTC.format(Instant.ofEpochSecond(epochSeconds));
    }

    private static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        return HmacSigner.raw(key, "HMAC-SHA256", data);
    }

    private static String hex(byte[] data) {
        return HexFormat.of().formatHex(data);
    }
}
