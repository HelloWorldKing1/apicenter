package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.dto.InboundCredentialDtos.PoolLabelRequest;
import com.deepx.apicenter.dto.InboundCredentialDtos.PoolPrepareRequest;
import com.deepx.apicenter.dto.InboundCredentialDtos.PoolUpdateRequest;
import com.deepx.apicenter.service.InboundCredentialService;
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
 * **入站鉴权凭证池**管理端点（2026-09-24 v1.2 / D-CA-18）—— 三级属主统一入口。
 *
 * <p>路径前缀 `/api/admin/inbound-credentials`（`AdminAuthFilter` 自动保护：VIEWER 只读、写端点需 ADMIN/OWNER，
 * **无需新增权限代码**）。
 *
 * <p><b>与既有 `/clients/{clientId}/credentials` 的关系</b>：后者**保留为等价委托**（等价于
 * `ownerType=CLIENT&ownerId={clientId}`）—— 不删、不加新功能；新能力（接口池 / 平台池 / 备注）一律加在这里。
 * 前端可逐步从「调用方管理」页切到凭证池页（v1.2 的降级路径）。
 *
 * <p>⚠️ 凭证值**永不回显**（仅尾 4 指纹）；`prepare` 生成的明文**仅响应出现一次**。
 */
@RestController
@RequestMapping("/api/admin/inbound-credentials")
public class InboundCredentialController {

    private final InboundCredentialService credentialService;

    public InboundCredentialController(InboundCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /** 列表（遮显）：`ownerType` 必传（PLATFORM / INTERFACE / CLIENT）；PLATFORM 时 `ownerId` 须留空 */
    @GetMapping
    public ApiResult<List<CredentialView>> list(@RequestParam String ownerType,
                                                @RequestParam(required = false) String ownerId) {
        return ApiResult.ok(credentialService.list(ownerType, ownerId));
    }

    /** 新增 / 轮换：平台生成随机值，明文仅本次回显（交付给调用方配置） */
    @PostMapping
    public ApiResult<CredentialIssuedView> prepare(@Valid @RequestBody PoolPrepareRequest req) {
        return ApiResult.ok(credentialService.prepare(req.ownerType(), req.ownerId(), req.kind(), req.label()));
    }

    /** 录入（第三方给的密钥）：一步 ACTIVE，旧 ACTIVE 转入 ROTATING 并存 24h */
    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable long id, @Valid @RequestBody PoolUpdateRequest req) {
        credentialService.update(req.ownerType(), req.ownerId(), req.kind(), req.credential(), req.label());
        return ApiResult.ok();
    }

    /** 仅改备注（「发给谁 / 何时」）：账号出事时靠它叫得上人 */
    @PutMapping("/{id}/label")
    public ApiResult<Void> updateLabel(@PathVariable long id, @RequestParam String ownerType,
                                       @RequestParam(required = false) String ownerId,
                                       @RequestBody PoolLabelRequest req) {
        credentialService.updateLabel(ownerType, ownerId, id, req == null ? null : req.label());
        return ApiResult.ok();
    }

    /** 激活轮换：目标 ROTATING → ACTIVE，旧 ACTIVE → ROTATING（并存 +24h） */
    @PostMapping("/{id}/activate")
    public ApiResult<Void> activate(@PathVariable long id, @RequestParam String ownerType,
                                    @RequestParam(required = false) String ownerId) {
        credentialService.activate(ownerType, ownerId, id);
        return ApiResult.ok();
    }

    /** 完成轮换（提前收尾）：ROTATING → RETIRED */
    @PostMapping("/{id}/finish-rotation")
    public ApiResult<Void> finishRotation(@PathVariable long id, @RequestParam String ownerType,
                                          @RequestParam(required = false) String ownerId) {
        credentialService.finishRotation(ownerType, ownerId, id);
        return ApiResult.ok();
    }

    /**
     * 即时失效（**单独吊销某个调用方的正式手段**）：目标 → RETIRED；
     * 若该类型已无 ACTIVE 凭证，msg 返回告警文案（引导补发）。
     */
    @PostMapping("/{id}/retire")
    public ApiResult<String> retire(@PathVariable long id, @RequestParam String ownerType,
                                    @RequestParam(required = false) String ownerId) {
        // ⚠️ 告警文案走 **data**（`ApiResult.ok(msg)`）而不是 `error(0, msg)`：
        //    前端 `api/http.js` 在 `code===0` 时只取 `data`，塞进 msg 会被**静默吞掉**
        //    —— 与既有 `/apps/{id}/credentials/{id}/retire`、`/clients/{id}/credentials/{id}/retire` 同口径。
        return ApiResult.ok(credentialService.retire(ownerType, ownerId, id));
    }

    /** 删除（仅 RETIRED 可删，状态机保护） */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id, @RequestParam String ownerType,
                                  @RequestParam(required = false) String ownerId) {
        credentialService.delete(ownerType, ownerId, id);
        return ApiResult.ok();
    }
}
