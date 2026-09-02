package com.deepx.apicenter.engine;

import com.deepx.apicenter.client.OutboundRequestSpec;
import com.deepx.apicenter.model.InterfaceRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链上下文（M0-01 §3，定稿 D2）：payload / outbound 允许原地修改（单请求单线程、链内顺序执行），
 * 其余字段只读；attrs 为扩展位（适配器配置 params、出站凭证等经此传递）。
 */
public final class AdapterContext {

    /** 当前链阶段（链引擎推进，适配器按此分流） */
    private ChainPhase phase;

    /** 统一内部模型（链内唯一数据载体，原地修改） */
    private final UnifiedModel payload;

    /** 接口元数据 */
    private final InterfaceMeta iface;

    /** 应用（供应商）元数据 */
    private final AppMeta app;

    /** 链路追踪 */
    private final TraceMeta trace;

    /** 入站鉴权结果（INBOUND_AUTH 阶段写入） */
    private final AuthResult inboundAuth;

    /** 出站请求规格（协议编码填 body、出站鉴权加凭证头，原地修改） */
    private final OutboundRequestSpec outbound;

    /** 告警收集（各适配器可追加，不阻断执行） */
    private final List<String> warnings;

    /** 扩展位：适配器 params（key=adapterParams）、出站凭证明文（key=outboundCredential）等 */
    private final Map<String, Object> attrs;

    public AdapterContext(ChainPhase phase, UnifiedModel payload, InterfaceMeta iface, AppMeta app,
                          TraceMeta trace, AuthResult inboundAuth, OutboundRequestSpec outbound,
                          List<String> warnings, Map<String, Object> attrs) {
        this.phase = phase;
        this.payload = payload;
        this.iface = iface;
        this.app = app;
        this.trace = trace;
        this.inboundAuth = inboundAuth;
        this.outbound = outbound;
        this.warnings = warnings;
        this.attrs = attrs;
    }

    public static AdapterContext create(ChainPhase phase, UnifiedModel payload, InterfaceMeta iface,
                                        AppMeta app, TraceMeta trace, AuthResult inboundAuth,
                                        OutboundRequestSpec outbound) {
        return new AdapterContext(phase, payload, iface, app, trace, inboundAuth, outbound,
                new ArrayList<>(), new LinkedHashMap<>());
    }

    public ChainPhase phase() {
        return phase;
    }

    public void phase(ChainPhase phase) {
        this.phase = phase;
    }

    public UnifiedModel payload() {
        return payload;
    }

    public InterfaceMeta iface() {
        return iface;
    }

    public AppMeta app() {
        return app;
    }

    public TraceMeta trace() {
        return trace;
    }

    public AuthResult inboundAuth() {
        return inboundAuth;
    }

    public OutboundRequestSpec outbound() {
        return outbound;
    }

    public List<String> warnings() {
        return warnings;
    }

    public Map<String, Object> attrs() {
        return attrs;
    }

    public void warn(String message) {
        warnings.add(message);
    }

    /** 接口元数据（链执行所需的最小集） */
    public record InterfaceMeta(long id, String code, String ifType, String method, String path,
                                String protocolIn, String protocolOut, String upstreamPath, String callbackUrl,
                                int timeoutMs, int maxRetries) {
        public static InterfaceMeta of(InterfaceRow row) {
            return new InterfaceMeta(row.id(), row.code(), row.ifType(), row.method(), row.path(),
                    row.protocolIn(), row.protocolOut(), row.upstreamPath(), row.callbackUrl(),
                    row.timeoutMs(), row.maxRetries());
        }
    }

    /** 应用（供应商）元数据 */
    public record AppMeta(String appId, String baseUrl) {
    }

    /** 链路追踪元数据 */
    public record TraceMeta(String traceId) {
    }

    /** 入站鉴权结果：通过与否 + 失败码（M0-01 §6：验签失败 40100/40101） */
    public record AuthResult(boolean passed, int errorCode, String errorMsg, String appId) {
        public static AuthResult pass(String appId) {
            return new AuthResult(true, 0, null, appId);
        }

        public static AuthResult fail(int errorCode, String errorMsg) {
            return new AuthResult(false, errorCode, errorMsg, null);
        }
    }
}
