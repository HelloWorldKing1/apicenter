package com.deepx.apicenter.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId MDC 过滤器（M4 交付，D-M4-5）：最前置解析业务 traceId（X-Trace-Id / traceparent
 * 请求头透传，无则生成），写入 MDC（日志 pattern 输出）与请求属性（网关读取同一值，
 * 保证「日志行 traceId = 运行表 trace_id = call_log.trace_id」三方一致——跨系统统一用业务 traceId 串联，
 * OTel 自身 traceId 独立，span 附 business.traceId 关联）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String ATTR_TRACE_ID = "APICENTER_TRACE_ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = firstHeader(request, "X-Trace-Id", "traceparent");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        } else if (traceId.startsWith("00-")) {
            // W3C traceparent 格式 00-<traceId>-<spanId>-<flags>：取 traceId 段
            String[] parts = traceId.split("-");
            if (parts.length > 1 && !parts[1].isBlank()) {
                traceId = parts[1];
            }
        }
        request.setAttribute(ATTR_TRACE_ID, traceId);
        MDC.put("traceId", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }

    private String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
