package com.deepx.apicenter.config;

import com.deepx.apicenter.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 报文大小第一道防线（M0-03 §1.3，评审 N2 修复）：
 * 在参数解析（@RequestBody 读取 body）之前按 Content-Length 预检超限请求——
 * 超限报文不读入内存即拒绝 40002（chunked 传输无 Content-Length 时由网关 handler 内的实际字节检查兜底）。
 * 拦截路径：接入层通配路由（/api/admin 管理面与 /actuator 不在此限）。
 */
@Component
public class BodySizeLimitInterceptor implements HandlerInterceptor {

    @Value("${app.api-center.max-body-bytes:1048576}")
    private long maxBodyBytes;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBodyBytes) {
            throw new BizException(40002, "报文超过大小限制（" + maxBodyBytes + " 字节）");
        }
        return true;
    }
}
