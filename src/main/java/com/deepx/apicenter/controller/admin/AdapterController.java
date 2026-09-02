package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.dto.AdapterDtos.AdapterRequest;
import com.deepx.apicenter.dto.AdapterDtos.AdapterResponse;
import com.deepx.apicenter.dto.AdapterDtos.ImplMeta;
import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.service.AdapterService;
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
 * 适配器管理（M1 注册表骨架）：CRUD + impl 元数据清单（管理面动态渲染参数表单）+ 启停。
 */
@RestController
@RequestMapping("/api/admin/adapters")
public class AdapterController {

    private final AdapterService adapterService;

    public AdapterController(AdapterService adapterService) {
        this.adapterService = adapterService;
    }

    @GetMapping
    public ApiResult<List<AdapterResponse>> list(@RequestParam(required = false) String type) {
        return ApiResult.ok(adapterService.list(type));
    }

    /** impl 元数据清单：前端据此动态渲染参数表单（原型 ADAPTER_FIELDS 模式） */
    @GetMapping("/impls")
    public ApiResult<List<ImplMeta>> impls() {
        return ApiResult.ok(adapterService.implCatalog());
    }

    @PostMapping
    public ApiResult<Void> create(@Valid @RequestBody AdapterRequest req) {
        adapterService.create(req);
        return ApiResult.ok();
    }

    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable String id, @Valid @RequestBody AdapterRequest req) {
        adapterService.update(id, req);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/enable")
    public ApiResult<Void> enable(@PathVariable String id) {
        adapterService.enable(id, true);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/disable")
    public ApiResult<Void> disable(@PathVariable String id) {
        adapterService.enable(id, false);
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        adapterService.delete(id);
        return ApiResult.ok();
    }
}
