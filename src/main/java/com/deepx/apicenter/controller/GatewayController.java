package com.deepx.apicenter.controller;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.engine.OutboundEngine;
import jakarta.servlet.http.HttpServletRequest;
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

    private final OutboundEngine outboundEngine;

    public GatewayController(OutboundEngine outboundEngine) {
        this.outboundEngine = outboundEngine;
    }

    @RequestMapping(value = "/{*path}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<ApiResult<?>> gateway(@PathVariable String path,
                                                @RequestBody(required = false) byte[] body,
                                                HttpServletRequest request) {
        // Spring 7 的 {*path} 通配捕获值自带前导斜杠，此处统一归一化为单个前导斜杠
        // （否则拼成 "//fastmoss/creatorList" 与接口库路径精确匹配失败）
        String p = path == null ? "" : path.replaceAll("^/+", "");
        String fullPath = p.isEmpty() ? "/" : "/" + p;
        // traceId 透传（M4 OTel 埋点前先透传请求头）与业务键（上游幂等依赖，ADR 5）
        String traceId = firstHeader(request, "X-Trace-Id", "traceparent");
        String bizId = firstHeader(request, "X-Biz-Id");
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
