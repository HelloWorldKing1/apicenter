package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.config.AdminAuthFilter;
import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.AuthDtos.AuthStatusView;
import com.deepx.apicenter.dto.AuthDtos.ChangePasswordRequest;
import com.deepx.apicenter.dto.AuthDtos.LoginRequest;
import com.deepx.apicenter.dto.AuthDtos.LoginView;
import com.deepx.apicenter.dto.AuthDtos.RegisterRequest;
import com.deepx.apicenter.dto.AuthDtos.UserView;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdminUserRow;
import com.deepx.apicenter.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理面账号：登录 / 注册 / 退出 / 改密 / 当前用户 / 登录页状态（2026-09-18）。
 *
 * <p>鉴权由 {@link AdminAuthFilter} 统一处理（`/api/admin/**` 默认需登录）；
 * 本控制器仅 `login` / `register` / `status` 三个端点在过滤器豁免清单里（登录页自身要能访问）。
 */
@RestController
@RequestMapping("/api/admin/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录页引导信息（免鉴权）：认证开关 / 是否已有账号 / 是否允许注册 */
    @GetMapping("/status")
    public ApiResult<AuthStatusView> status() {
        return ApiResult.ok(authService.status());
    }

    @PostMapping("/login")
    public ApiResult<LoginView> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        return ApiResult.ok(authService.login(req.username(), req.password(), clientInfo(request)));
    }

    @PostMapping("/register")
    public ApiResult<LoginView> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest request) {
        return ApiResult.ok(authService.register(req.username(), req.password(), req.displayName(), clientInfo(request)));
    }

    /** 退出：删除当前令牌（即时失效） */
    @PostMapping("/logout")
    public ApiResult<Void> logout(HttpServletRequest request) {
        authService.logout(AdminAuthFilter.currentTokenHash(request));
        return ApiResult.ok();
    }

    @GetMapping("/me")
    public ApiResult<UserView> me(HttpServletRequest request) {
        AdminUserRow user = current(request);
        return ApiResult.ok(authService.currentUser(user.id())
                .orElseThrow(() -> new BizException(BizException.UNAUTHORIZED, "登录已失效，请重新登录")));
    }

    /** 改密：校验原密码 → 更新 → 吊销该账号其他会话（当前会话保留） */
    @PostMapping("/password")
    public ApiResult<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req, HttpServletRequest request) {
        AdminUserRow user = current(request);
        authService.changePassword(user.id(), AdminAuthFilter.currentTokenHash(request),
                req.oldPassword(), req.newPassword());
        return ApiResult.ok();
    }

    private AdminUserRow current(HttpServletRequest request) {
        AdminUserRow user = AdminAuthFilter.currentUser(request);
        if (user == null) {
            throw new BizException(BizException.UNAUTHORIZED, "未登录或登录已过期，请重新登录");
        }
        return user;
    }

    /** 登录来源（诊断用，截断后入库）：UA 摘 + 客户端地址 */
    private String clientInfo(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        } else {
            ip = ip.split(",")[0].trim();
        }
        String info = ip + " | " + (ua == null ? "-" : ua);
        return info.length() <= 200 ? info : info.substring(0, 200);
    }
}
