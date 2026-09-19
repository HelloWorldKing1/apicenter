package com.deepx.apicenter.config;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdminUserRow;
import com.deepx.apicenter.service.AuthService;
import com.deepx.apicenter.service.PasswordHasher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * 管理面认证过滤器（2026-09-18）：保护 `/api/admin/**`，要求 `Authorization: Bearer <token>`。
 *
 * <p><b>保护范围</b>：仅管理面 REST；**不含**平台对外接口路径（`/{platformPath}`，入站回调与出站调用）——
 * 调用方鉴权是另一特性（设计 §1.2 / §5.3）。`/actuator/health` 与静态资源同样放行。
 *
 * <p><b>豁免</b>：`/api/admin/auth/{login,register,status}`（登录页自身需要）+ `OPTIONS` 预检
 * （CORS 预检不带凭据，拦掉会让浏览器所有写操作失败，是 2026-09-18 CORS 事故的同类坑）。
 *
 * <p><b>失败语义</b>：统一信封 `{code:40104,...}` + HTTP 401（前端据此清 token 并跳登录页）；
 * 源码级开关 `auth.enabled=false` 时**完全不校验**（集成测试与线上应急回退用）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@EnableConfigurationProperties(AuthProperties.class)
public class AdminAuthFilter extends OncePerRequestFilter {

    /** 请求属性：当前登录账号（Controller 用 {@link #currentUser(HttpServletRequest)} 取） */
    public static final String ATTR_USER = "APICENTER_ADMIN_USER";
    /** 请求属性：当前令牌摘要（改密时用于「保留当前会话」） */
    public static final String ATTR_TOKEN_HASH = "APICENTER_ADMIN_TOKEN_HASH";

    private static final String PREFIX = "/api/admin";
    private static final Set<String> EXEMPT = Set.of(
            "/api/admin/auth/login", "/api/admin/auth/register", "/api/admin/auth/status");

    private final AuthProperties props;
    private final AuthService authService;
    private final PasswordHasher hasher;
    private final ObjectMapper objectMapper;

    public AdminAuthFilter(AuthProperties props, AuthService authService, PasswordHasher hasher,
                           ObjectMapper objectMapper) {
        this.props = props;
        this.authService = authService;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!props.enabledOrDefault()
                || !path.startsWith(PREFIX)
                || EXEMPT.contains(path)
                || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = bearer(request);
        if (token == null) {
            reject(response, "未登录或登录已过期，请重新登录");
            return;
        }
        Optional<AdminUserRow> user = authService.authenticate(token);
        if (user.isEmpty()) {
            reject(response, "登录已失效，请重新登录");
            return;
        }
        request.setAttribute(ATTR_USER, user.get());
        request.setAttribute(ATTR_TOKEN_HASH, hasher.sha256Hex(token));
        filterChain.doFilter(request, response);
    }

    /** 当前登录账号（Controller 侧取用；未登录返回 null） */
    public static AdminUserRow currentUser(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR_USER);
        return v instanceof AdminUserRow row ? row : null;
    }

    public static String currentTokenHash(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR_TOKEN_HASH);
        return v instanceof String s ? s : null;
    }

    private String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = trimmed.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null; // 只接受 Bearer 方案（避免歧义：不放行裸 token）
    }

    /** 401 + 统一信封（与 GlobalExceptionHandler 同构：code=40104 → HTTP 401） */
    private void reject(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(BizException.UNAUTHORIZED / 100);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResult.error(BizException.UNAUTHORIZED, msg)));
    }
}
