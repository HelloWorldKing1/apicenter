package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceResponse;
import com.deepx.apicenter.engine.OutboundEngine;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.InterfaceRepository;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 接口管理：完整定义模型（主表 + 参数 / Body / 字段映射 / 响应·ack / 绑定子表）；
 * 更新为全量替换 + version 乐观锁；生命周期 草稿 → 发布 → 下线；
 * 另提供「测试接口」——以给定请求体真实走一遍出站链路（管理面调试用）。
 */
@RestController
@RequestMapping("/api/admin/interfaces")
public class InterfaceController {

    private final InterfaceService interfaceService;
    private final InterfaceRepository interfaceRepository;
    private final OutboundEngine outboundEngine;

    public InterfaceController(InterfaceService interfaceService,
                               InterfaceRepository interfaceRepository,
                               OutboundEngine outboundEngine) {
        this.interfaceService = interfaceService;
        this.interfaceRepository = interfaceRepository;
        this.outboundEngine = outboundEngine;
    }

    @GetMapping
    public ApiResult<List<InterfaceResponse>> list(@RequestParam(required = false) String appId,
                                                   @RequestParam(required = false) Long groupId,
                                                   @RequestParam(required = false) String ifType,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String keyword) {
        return ApiResult.ok(interfaceService.list(appId, groupId, ifType, status, keyword));
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

    /**
     * 测试接口（管理面调试）：以给定请求体真实走一遍出站链路（链执行 + 状态机），
     * 与接入层路由的区别：不做 PUBLISHED / 方法校验（草稿态也可测）、不要求经平台路径。
     * 失败分支（死信 / 补偿 / UNKNOWN）由全局异常处理返回对应错误信封（msg 含死信编号等诊断信息）。
     */
    @PostMapping("/{id}/test")
    public ApiResult<?> test(@PathVariable long id, @RequestBody(required = false) String body) {
        InterfaceRow iface = interfaceRepository.findById(id)
                .orElseThrow(() -> BizException.ifaceNotFound(id));
        byte[] raw = body == null || body.isBlank()
                ? "{}".getBytes(StandardCharsets.UTF_8)
                : body.getBytes(StandardCharsets.UTF_8);
        return outboundEngine.execute(iface, raw,
                "TEST-" + UUID.randomUUID().toString().substring(0, 8), null);
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
