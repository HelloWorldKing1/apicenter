package com.deepx.apicenter.controller;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.engine.InboundEngine;
import com.deepx.apicenter.engine.OutboundEngine;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.InterfaceRepository;
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

    /** 报文大小上限（M0-03 §1.3 默认 1MB，超限 40002；出站请求与入站回调同网关入口，双向生效） */
    @Value("${app.api-center.max-body-bytes:1048576}")
    private long maxBodyBytes;

    public GatewayController(OutboundEngine outboundEngine,
                             InboundEngine inboundEngine,
                             InterfaceRepository interfaceRepository) {
        this.outboundEngine = outboundEngine;
        this.inboundEngine = inboundEngine;
        this.interfaceRepository = interfaceRepository;
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
        // traceId 透传（M4 OTel 埋点前先透传请求头）与业务键（上游幂等依赖，ADR 5）
        String traceId = firstHeader(request, "X-Trace-Id", "traceparent");
        String bizId = firstHeader(request, "X-Biz-Id");
        log.info("接入层请求 method={} path={} bizId={} traceId={} bodyBytes={}",
                request.getMethod(), fullPath, bizId, traceId, body == null ? 0 : body.length);
        // 报文大小限制（M0-03 §1.3，M3 补齐）：超限 40002，防大报文拖垮链（双向生效）
        if (body != null && body.length > maxBodyBytes) {
            throw new BizException(40002, "报文超过大小限制（" + maxBodyBytes + " 字节）");
        }
        // M3：按 if_type 分流——INBOUND → 入站执行引擎（成功回裸 ack 报文；
        // 验签 / 链失败抛 BizException → 全局异常处理转统一信封，不落运行表）
        InterfaceRow routed = interfaceRepository.findByPath(fullPath).orElse(null);
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
