package com.deepx.apicenter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：本地前端联调 CORS 兜底（Vite dev server 5173 经 proxy 转发为主，CORS 兜底）；
 * 报文大小预检拦截器（评审 N2：接入层路由先于 body 读取拒绝超限请求）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final BodySizeLimitInterceptor bodySizeLimitInterceptor;

    public WebConfig(BodySizeLimitInterceptor bodySizeLimitInterceptor) {
        this.bodySizeLimitInterceptor = bodySizeLimitInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
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
