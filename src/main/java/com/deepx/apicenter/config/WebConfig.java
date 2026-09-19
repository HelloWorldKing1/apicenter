package com.deepx.apicenter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Web 配置：本地前端联调 CORS（Vite dev server 经 proxy 转发为主，CORS 为兜底）+
 * 报文大小预检拦截器（评审 N2：接入层路由先于 body 读取拒绝超限请求）。
 *
 * <p><b>2026-09-18 修复（真实缺陷）</b>：原实现把来源写死为单个 `http://localhost:5173`。Spring 只跳过
 * **同源**请求的 CORS 校验，`127.0.0.1` 与 `localhost` 是**不同 Origin** —— 于是：
 * <ul>
 *   <li>用 `http://127.0.0.1:5173` 打开前端 → 所有**写操作**（POST/PUT/DELETE，浏览器会带 Origin）被回
 *       **403 "Invalid CORS request"**（GET 不带 Origin 所以看起来「只有保存/测试接口报错」）；</li>
 *   <li>5173 端口被占、Vite 自动改用 5174 时，同样 403；</li>
 *   <li>症状容易误判为业务故障：实际是 CORS 层拒绝，请求**根本没进引擎**（无 call_log/运行记录）。</li>
 * </ul>
 * 现改为**模式匹配**（默认本机回环任意端口）：`allowedOriginPatterns` 支持精确 Origin 与 `host:[*]` 端口通配。
 * 生产同源部署（静态资源由本服务提供）或反向代理无需 CORS；确需跨域时按环境配置本属性，
 * 置空则**不注册** CORS 配置（Spring 不做 CORS 处理，同源/代理天然可用）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 允许的跨域来源模式（逗号分隔；默认本机回环任意端口）。
     * 例：`http://localhost:[*],http://127.0.0.1:[*]`；精确来源直接写 `https://admin.example.com`。
     */
    @Value("${app.api-center.cors.allowed-origin-patterns:http://localhost:[*],http://127.0.0.1:[*]}")
    private String allowedOriginPatterns;

    private final BodySizeLimitInterceptor bodySizeLimitInterceptor;

    public WebConfig(BodySizeLimitInterceptor bodySizeLimitInterceptor) {
        this.bodySizeLimitInterceptor = bodySizeLimitInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> patterns = Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (patterns.isEmpty()) {
            return; // 空 = 不注册 CORS 配置（同源 / 反代场景天然可用，且不会误拒任何带 Origin 的请求）
        }
        registry.addMapping("/api/**")
                .allowedOriginPatterns(patterns.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(bodySizeLimitInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/api/admin/**", "/actuator/**");
    }
}
