package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.config.AdminAuthFilter;
import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.UserDtos.CreateUserRequest;
import com.deepx.apicenter.dto.UserDtos.ResetPasswordRequest;
import com.deepx.apicenter.dto.UserDtos.UpdateUserRequest;
import com.deepx.apicenter.dto.UserDtos.UserRowView;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdminUserRow;
import com.deepx.apicenter.service.AdminUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账号管理（v1，2026-09-18）：管理面账号的列表 / 新建 / 编辑（显示名·启停用）/ 重置口令 / 解锁 / 删除。
 *
 * <p>守卫不在本层：见 {@link AdminUserService} 的「不把系统锁死」两条底线（最后一个可用账号、不能动自己）。
 * v1 无角色 ⇒ 任何已登录账号都能调用本控制器；权限分级见《账号登录设计方案.md》§12。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService userService;

    public AdminUserController(AdminUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ApiResult<List<UserRowView>> list(@RequestParam(required = false) String keyword) {
        return ApiResult.ok(userService.list(keyword).stream()
                .map(r -> new UserRowView(r.id(), r.username(), r.displayName(), r.role(), r.status(), r.failedAttempts(),
                        r.lockedUntil(), r.lastLoginAt(), r.passwordUpdatedAt(), r.createdAt(), r.sessionCount()))
                .toList());
    }

    /** 新建账号（不自动建会话：新账号自行登录） */
    @PostMapping
    public ApiResult<Long> create(@Valid @RequestBody CreateUserRequest req, HttpServletRequest request) {
        return ApiResult.ok(userService.create(operatorId(request), operatorRole(request), req.username(),
                req.password(), req.displayName(), req.role()));
    }

    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable long id, @RequestBody UpdateUserRequest req,
                                  HttpServletRequest request) {
        userService.update(operatorId(request), operatorRole(request), id, req.displayName(), req.status(), req.role());
        return ApiResult.ok();
    }

    /** 重置口令（他人账号；重置即吊销该账号全部会话） */
    @PostMapping("/{id}/password")
    public ApiResult<Void> resetPassword(@PathVariable long id, @Valid @RequestBody ResetPasswordRequest req,
                                         HttpServletRequest request) {
        userService.resetPassword(operatorId(request), operatorRole(request), id, req.newPassword());
        return ApiResult.ok();
    }

    /** 解除锁定（连续失败达阈值后的恢复口） */
    @PostMapping("/{id}/unlock")
    public ApiResult<Void> unlock(@PathVariable long id, HttpServletRequest request) {
        userService.unlock(operatorId(request), operatorRole(request), id);
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id, HttpServletRequest request) {
        userService.delete(operatorId(request), operatorRole(request), id);
        return ApiResult.ok();
    }

    /** 操作者 = 当前登录账号（过滤器已注入；理论上不会为空） */
    private long operatorId(HttpServletRequest request) {
        return me(request).id();
    }

    /** 操作者角色（语义级校验用：ADMIN 不能动 OWNER、不能删账号等） */
    private String operatorRole(HttpServletRequest request) {
        return me(request).role();
    }

    private AdminUserRow me(HttpServletRequest request) {
        AdminUserRow me = AdminAuthFilter.currentUser(request);
        if (me == null) {
            throw new BizException(BizException.UNAUTHORIZED, "未登录或登录已过期，请重新登录");
        }
        return me;
    }
}
