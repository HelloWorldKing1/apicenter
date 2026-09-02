package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.AppDtos.AppResponse;
import com.deepx.apicenter.service.AppService;
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
 * 应用管理（应用 = 供应商）：CRUD + 生命周期操作（草稿 → 启用 → 停用 → 注销）。
 */
@RestController
@RequestMapping("/api/admin/apps")
public class AppController {

    private final AppService appService;

    public AppController(AppService appService) {
        this.appService = appService;
    }

    @GetMapping
    public ApiResult<List<AppResponse>> list(@RequestParam(required = false) String keyword) {
        return ApiResult.ok(appService.list(keyword));
    }

    @GetMapping("/{appId}")
    public ApiResult<AppResponse> detail(@PathVariable String appId) {
        return ApiResult.ok(appService.detail(appId));
    }

    @PostMapping
    public ApiResult<Void> create(@Valid @RequestBody AppRequest req) {
        appService.create(req);
        return ApiResult.ok();
    }

    @PutMapping("/{appId}")
    public ApiResult<Void> update(@PathVariable String appId, @Valid @RequestBody AppRequest req) {
        appService.update(appId, req);
        return ApiResult.ok();
    }

    @PostMapping("/{appId}/enable")
    public ApiResult<Void> enable(@PathVariable String appId) {
        appService.enable(appId);
        return ApiResult.ok();
    }

    @PostMapping("/{appId}/disable")
    public ApiResult<Void> disable(@PathVariable String appId) {
        appService.disable(appId);
        return ApiResult.ok();
    }

    @PostMapping("/{appId}/cancel")
    public ApiResult<Void> cancel(@PathVariable String appId) {
        appService.cancel(appId);
        return ApiResult.ok();
    }

    @DeleteMapping("/{appId}")
    public ApiResult<Void> delete(@PathVariable String appId) {
        appService.delete(appId);
        return ApiResult.ok();
    }
}
