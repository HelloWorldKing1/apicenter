package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceResponse;
import com.deepx.apicenter.service.InterfaceService;
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
 * 接口管理：完整定义模型（主表 + 参数 / Body / 字段映射 / 响应·ack / 绑定子表）；
 * 更新为全量替换 + version 乐观锁；生命周期 草稿 → 发布 → 下线。
 */
@RestController
@RequestMapping("/api/admin/interfaces")
public class InterfaceController {

    private final InterfaceService interfaceService;

    public InterfaceController(InterfaceService interfaceService) {
        this.interfaceService = interfaceService;
    }

    @GetMapping
    public ApiResult<List<InterfaceResponse>> list(@RequestParam(required = false) String appId,
                                                   @RequestParam(required = false) Long groupId) {
        return ApiResult.ok(interfaceService.list(appId, groupId));
    }

    @GetMapping("/{id}")
    public ApiResult<InterfaceResponse> detail(@PathVariable long id) {
        return ApiResult.ok(interfaceService.detail(id));
    }

    @PostMapping
    public ApiResult<Void> create(@Valid @RequestBody InterfaceRequest req) {
        interfaceService.create(req);
        return ApiResult.ok();
    }

    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable long id, @Valid @RequestBody InterfaceRequest req) {
        interfaceService.update(id, req);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/publish")
    public ApiResult<Void> publish(@PathVariable long id) {
        interfaceService.publish(id);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/offline")
    public ApiResult<Void> offline(@PathVariable long id) {
        interfaceService.offline(id);
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        interfaceService.delete(id);
        return ApiResult.ok();
    }
}
