package com.deepx.apicenter.controller;

import com.deepx.apicenter.config.TraceIdFilter;
import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.engine.InboundEngine;
import com.deepx.apicenter.engine.OutboundEngine;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.service.GatewayGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Enumeration;

/**
 * 接入层路由（执行面，Flow A）：平台侧路径（接口配置驱动）→ 出站执行引擎。
 * 路由键 = interface.path（全局唯一）；接口 PUBLISHED 才可路由（设计 §3.4 下线停路由）。
 * 精确映射（/api/admin、/actuator 等）优先于本通配映射，互不冲突。
 */
@RestController
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final OutboundEngine outboundEngine;
    private final InboundEngine inboundEngine;
    private final InterfaceRepository interfaceRepository;
    private final AppRepository appRepository;
    private final GatewayGuard gatewayGuard;

    /** 报文大小上限（M0-03 §1.3 默认 1MB，超限 40002；出站请求与入站回调同网关入口，双向生效） */
    @Value("${app.api-center.max-body-bytes:1048576}")
    private long maxBodyBytes;

    public GatewayController(OutboundEngine outboundEngine,
                             InboundEngine inboundEngine,
                             InterfaceRepository interfaceRepository,
                             AppRepository appRepository,
                             GatewayGuard gatewayGuard) {
        this.outboundEngine = outboundEngine;
        this.inboundEngine = inboundEngine;
        this.interfaceRepository = interfaceRepository;
        this.appRepository = appRepository;
        this.gatewayGuard = gatewayGuard;
    }

    @RequestMapping(value = "/{*path}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<?> gateway(@PathVariable String path,
                                     @RequestBody(required = false) byte[] body,
                                     HttpServletRequest request) {
        // Spring 7 的 {*path} 通配捕获值自带前导斜杠，此处统一归一化为单个前导斜杠
        // （否则拼成 "//fastmoss/creatorList" 与接口库路径精确匹配失败）
        String p = path == null ? "" : path.replaceAll("^/+", "");
        String fullPath = p.isEmpty() ? "/" : "/" + p;
        // traceId：优先取 TraceIdFilter 已解析并写入请求属性的同一值（保证 MDC / 运行表 / call_log 三方一致），
        // 兜底直读请求头（M4 OTel 埋点前先透传请求头）；业务键 X-Biz-Id（上游幂等依赖，ADR 5）
        String traceId = (String) request.getAttribute(TraceIdFilter.ATTR_TRACE_ID);
        if (traceId == null) {
            traceId = firstHeader(request, "X-Trace-Id", "traceparent");
        }
        String bizId = firstHeader(request, "X-Biz-Id");
        log.info("接入层请求 method={} path={} bizId={} traceId={} bodyBytes={}",
                request.getMethod(), fullPath, bizId, traceId, body == null ? 0 : body.length);
        // 报文大小限制第二道兜底（M0-03 §1.3，M3 补齐；第一道为 BodySizeLimitInterceptor 的 Content-Length 预检，
        // chunked 传输无 Content-Length 时在此按实际字节拒绝）：超限 40002（出站请求与入站回调双向生效）
        if (body != null && body.length > maxBodyBytes) {
            throw new BizException(40002, "报文超过大小限制（" + maxBodyBytes + " 字节）");
        }
        // M3：按 if_type 分流——INBOUND → 入站执行引擎（成功回裸 ack 报文；
        // 验签 / 链失败抛 BizException → 全局异常处理转统一信封，不落运行表）
        InterfaceRow routed = interfaceRepository.findByPath(fullPath).orElse(null);
        // M4 接入层防护（D-M4-6，补 M2 缺口）：QPS 限流 / 日配额 / IP 黑白名单——
        // 路由命中后、引擎执行前（落 outbound_request 之前），拒绝不污染状态机；
        // 应用启用校验仍由引擎承担（40102），此处仅限流配额与来源控制
        if (routed != null) {
            appRepository.findById(routed.appId()).ifPresent(app ->
                    gatewayGuard.check(app, gatewayGuard.resolveClientIp(
                            request.getRemoteAddr(), request.getHeader("X-Forwarded-For"))));
        }
        if (routed != null && "INBOUND".equals(routed.ifType())) {
            return inboundEngine.handle(request, fullPath, request.getMethod(), body, traceId);
        }
        ApiResult<?> result = outboundEngine.dispatch(fullPath, request.getMethod(), body, bizId, traceId);
        return ResponseEntity.ok(result);
    }

    private String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            Enumeration<String> values = request.getHeaders(name);
            if (values != null && values.hasMoreElements()) {
                return values.nextElement();
            }
        }
        return null;
    }
}
