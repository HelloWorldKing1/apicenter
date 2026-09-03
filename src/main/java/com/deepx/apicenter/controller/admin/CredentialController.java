package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.dto.CredentialDtos.PrepareRequest;
import com.deepx.apicenter.dto.CredentialDtos.ResetRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.service.CredentialService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 凭证管理（M0-04）：签发 / 轮换 / 激活 / 重置 / 即时失效 / 完成轮换。
 * 管理面永不回显明文；prepare 生成的明文仅回显一次（生成响应）。
 */
@RestController
@RequestMapping("/api/admin/apps/{appId}/credentials")
public class CredentialController {

    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /** 遮显列表（指纹 + 状态，永不回显明文） */
    @GetMapping
    public ApiResult<List<CredentialView>> list(@PathVariable String appId) {
        return ApiResult.ok(credentialService.listViews(appId));
    }

    /** 生成新凭证（出站轮换第一步：ROTATING 待激活；明文仅本次回显） */
    @PostMapping("/prepare")
    public ApiResult<CredentialIssuedView> prepare(@PathVariable String appId,
                                                   @Valid @RequestBody PrepareRequest req) {
        return ApiResult.ok(credentialService.prepare(appId, req));
    }

    /** 激活（出站轮换第二步：旧 ACTIVE → ROTATING 并存 24h，新 → ACTIVE） */
    @PostMapping("/{id}/activate")
    public ApiResult<Void> activate(@PathVariable String appId, @PathVariable long id) {
        credentialService.activate(appId, id);
        return ApiResult.ok();
    }

    /** 一步更新（供应商主动轮换回调密钥：新 → ACTIVE，旧 → ROTATING 并存 24h） */
    @PostMapping("/update")
    public ApiResult<Void> update(@PathVariable String appId, @Valid @RequestBody UpdateRequest req) {
        credentialService.update(appId, req);
        return ApiResult.ok();
    }

    /** 重置（应急语义：新 → ACTIVE，旧全部立即 RETIRED） */
    @PostMapping("/reset")
    public ApiResult<Void> reset(@PathVariable String appId, @Valid @RequestBody ResetRequest req) {
        credentialService.reset(appId, req);
        return ApiResult.ok();
    }

    /** 即时失效（泄漏应急；若无剩余 ACTIVE，data 携带告警信息） */
    @PostMapping("/{id}/retire")
    public ApiResult<String> retire(@PathVariable String appId, @PathVariable long id) {
        return ApiResult.ok(credentialService.retire(appId, id));
    }

    /** 删除已失效凭证（仅 RETIRED 可删；ACTIVE/ROTATING 受保护） */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String appId, @PathVariable long id) {
        credentialService.delete(appId, id);
        return ApiResult.ok();
    }

    /** 完成轮换（ROTATING → RETIRED 提前收尾） */
    @PostMapping("/{id}/finish-rotation")
    public ApiResult<Void> finishRotation(@PathVariable String appId, @PathVariable long id) {
        credentialService.finishRotation(appId, id);
        return ApiResult.ok();
    }
}
