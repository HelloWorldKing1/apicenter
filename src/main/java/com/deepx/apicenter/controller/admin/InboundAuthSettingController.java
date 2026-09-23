package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.config.AdminAuthFilter;
import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.InboundAuthSettingDtos.ImpactView;
import com.deepx.apicenter.dto.InboundAuthSettingDtos.SettingRequest;
import com.deepx.apicenter.dto.InboundAuthSettingDtos.SettingView;
import com.deepx.apicenter.model.AdminUserRow;
import com.deepx.apicenter.service.InboundAuthSettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入站鉴权平台设置（2026-09-24 v1.2）——「平台默认方式」与「是否强制自报主体」的读写入口。
 *
 * <p>路径前缀 `/api/admin/inbound-auth`（`AdminAuthFilter` 自动保护：VIEWER 只读、写端点需 ADMIN/OWNER，
 * **无需新增权限代码**）。
 *
 * <p>⚠️ 与「接口级鉴权方式」的分工：接口绑定 `CLIENT_AUTH` 角色**优先于**此处设置；
 * 本页影响的是**未绑定**的接口（数量可用 {@code /settings/impact} 预览 —— 前端二次确认用）。
 */
@RestController
@RequestMapping("/api/admin/inbound-auth")
public class InboundAuthSettingController {

    private final InboundAuthSettingService settingService;

    public InboundAuthSettingController(InboundAuthSettingService settingService) {
        this.settingService = settingService;
    }

    /** 读平台设置（含最后修改人/时间） */
    @GetMapping("/settings")
    public ApiResult<SettingView> view() {
        return ApiResult.ok(settingService.view());
    }

    /** 保存（**即时生效，无需重启**：写库 + 失效缓存 + 发布变更事件） */
    @PutMapping("/settings")
    public ApiResult<SettingView> save(@Valid @RequestBody SettingRequest req, HttpServletRequest request) {
        return ApiResult.ok(settingService.save(req.defaultAdapterId(), req.requireClientId(), currentOperator(request)));
    }

    /** 影响面预览：本次变更会影响多少个接口（未绑定 `CLIENT_AUTH` 的出站中转接口） */
    @GetMapping("/settings/impact")
    public ApiResult<ImpactView> impact() {
        int n = settingService.affectedInterfaceCount();
        return ApiResult.ok(new ImpactView(n, n == 0
                ? "所有出站中转接口都已单独绑定鉴权方式，改平台默认不影响它们"
                : "有 " + n + " 个出站中转接口未单独绑定鉴权方式，将按平台默认判定"));
    }

    /** 操作者（审计留痕）：取管理面登录会话的用户名（`AdminAuthFilter` 已注入）；无（如 `auth.enabled=false`）时记 system */
    private String currentOperator(HttpServletRequest request) {
        AdminUserRow me = AdminAuthFilter.currentUser(request);
        return me == null || me.username() == null || me.username().isBlank() ? "system" : me.username();
    }
}
