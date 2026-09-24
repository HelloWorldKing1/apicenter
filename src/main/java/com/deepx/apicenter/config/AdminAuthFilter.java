package com.deepx.apicenter.config;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdminUserRow;
import com.deepx.apicenter.service.AuthService;
import com.deepx.apicenter.service.PasswordHasher;
import com.deepx.apicenter.service.RoleRules;
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
 * <p><b>角色强制（RBAC 第一层，2026-09-18）</b>：认证通过后再判角色，**只有两条规则**（其余权限差异在
 * {@code AdminUserService} 做语义级校验）：
 * <ul>
 *   <li>`VIEWER`（只读）执行**非 GET** 管理面请求 → 403 `40302`（`/api/admin/auth/**` 除外：
 *       改自己口令、退出登录属于个人操作，任何角色都该能做）；</li>
 *   <li>`/api/admin/users/**` 要求 `OWNER` / `ADMIN` → 否则 403 `40303`（VIEWER 连入口都不可达）。</li>
 *   <li>`/api/admin/inbound-auth/**` 与 `/api/admin/inbound-credentials/**`（入站鉴权管理）**读写都**要求
 *       `OWNER`/`ADMIN` → 否则 403 `40305`（VIEWER 连页面都进不去）；
 *       其中 `PUT /api/admin/inbound-auth/settings`（**平台设置**）再收紧到 `OWNER` → 403 `40304`。</li>
 * </ul>
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
        AdminUserRow me = user.get();
        String role = me.role();
        // 规则 1：只读角色不能执行写操作（个人账号操作 /auth/** 除外）
        if (RoleRules.isReadOnly(role) && !isReadMethod(request) && !path.startsWith(PREFIX + "/auth/")) {
            rejectRole(response, BizException.READ_ONLY_ROLE,
                    "当前角色（" + role + "）为只读，不能执行该写操作（需要 ADMIN 或 OWNER）");
            return;
        }
        // 规则 2：账号管理仅 OWNER / ADMIN
        if (path.startsWith(PREFIX + "/users") && !RoleRules.canManageAccounts(role)) {
            rejectRole(response, BizException.NO_ACCOUNT_ADMIN,
                    "当前角色（" + role + "）无账号管理权限（需要 ADMIN 或 OWNER）");
            return;
        }
        // 规则 3：入站鉴权管理**读写都要求 OWNER/ADMIN**（2026-09-24 决策 B：VIEWER 连页面与接口都不可达）
        //         理由：平台设置与凭证池台账是"安全配置面"（平台默认方式 / 有哪些密钥 / 指纹）
        if ((path.startsWith(PREFIX + "/inbound-auth") || path.startsWith(PREFIX + "/inbound-credentials"))
                && !RoleRules.canManageInboundAuth(role)) {
            rejectRole(response, BizException.NO_INBOUND_AUTH_ADMIN,
                    "当前角色（" + role + "）无入站鉴权管理权限（需要 ADMIN 或 OWNER）");
            return;
        }
        // 规则 4：其中**修改平台设置**再收紧到 OWNER（安全策略类写操作：一改就是全平台放宽/收紧）
        if (path.equals(PREFIX + "/inbound-auth/settings") && !isReadMethod(request)
                && !RoleRules.canChangeInboundAuthSetting(role)) {
            rejectRole(response, BizException.OWNER_ONLY,
                    "当前角色（" + role + "）不能修改入站鉴权的平台设置（仅 OWNER；"
                            + "凭证发放/吊销等日常操作仍可由 ADMIN 执行）");
            return;
        }
        request.setAttribute(ATTR_USER, me);
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
        write(response, BizException.UNAUTHORIZED, msg);
    }

    /** 403 + 统一信封（角色不足） */
    private void rejectRole(HttpServletResponse response, int code, String msg) throws IOException {
        write(response, code, msg);
    }

    /** HTTP 状态码 = 业务码 / 100（全库统一约定） */
    private void write(HttpServletResponse response, int code, String msg) throws IOException {
        response.setStatus(code / 100);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResult.error(code, msg)));
    }

    /** 只读方法判定（GET/HEAD；OPTIONS 已在前面放行） */
    private boolean isReadMethod(HttpServletRequest request) {
        String m = request.getMethod();
        return "GET".equalsIgnoreCase(m) || "HEAD".equalsIgnoreCase(m);
    }
}
