package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.ClientDtos.ClientDetail;
import com.deepx.apicenter.dto.ClientDtos.ClientRequest;
import com.deepx.apicenter.dto.ClientDtos.ClientResponse;
import com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.dto.CredentialDtos.PrepareRequest;
import com.deepx.apicenter.dto.CredentialDtos.ResetRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.service.ClientCredentialService;
import com.deepx.apicenter.service.ClientService;
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
 * 调用方（平台客户 / 接入方）管理 —— 2026-09-23 入站鉴权 **B1「数据与目录」**。
 *
 * <p>路径前缀 `/api/admin/clients`（`AdminAuthFilter` 自动保护：VIEWER 只读、写端点需 ADMIN/OWNER，无需新增权限代码）。
 * 凭证子资源与 {@link CredentialController}（应用凭证）**同形**，便于前端复用交互与文案。
 *
 * <p>⚠️ 鉴权判定（闸门）属 B2/B3，不在本控制器。
 */
@RestController
@RequestMapping("/api/admin/clients")
public class ClientController {

    private final ClientService clientService;
    private final ClientCredentialService credentialService;

    public ClientController(ClientService clientService, ClientCredentialService credentialService) {
        this.clientService = clientService;
        this.credentialService = credentialService;
    }

    /** 列表（含凭证角标 `activeCredentialKinds`） */
    @GetMapping
    public ApiResult<List<ClientResponse>> list(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String status) {
        return ApiResult.ok(clientService.list(keyword, status));
    }

    /** 新建（标识格式校验 + 全库唯一；初始 `ENABLED`） */
    @PostMapping
    public ApiResult<ClientResponse> create(@Valid @RequestBody ClientRequest req) {
        return ApiResult.ok(clientService.create(req));
    }

    /** 详情（含凭证遮显列表） */
    @GetMapping("/{clientId}")
    public ApiResult<ClientDetail> detail(@PathVariable String clientId) {
        return ApiResult.ok(clientService.detail(clientId));
    }

    /** 更新（标识不可改） */
    @PutMapping("/{clientId}")
    public ApiResult<ClientResponse> update(@PathVariable String clientId,
                                            @Valid @RequestBody ClientRequest req) {
        return ApiResult.ok(clientService.update(clientId, req));
    }

    /** 启用（幂等） */
    @PostMapping("/{clientId}/enable")
    public ApiResult<Void> enable(@PathVariable String clientId) {
        clientService.setStatus(clientId, "ENABLED");
        return ApiResult.ok();
    }

    /** 停用（幂等；停用即拒 40107） */
    @PostMapping("/{clientId}/disable")
    public ApiResult<Void> disable(@PathVariable String clientId) {
        clientService.setStatus(clientId, "DISABLED");
        return ApiResult.ok();
    }

    /** 删除（级联删凭证；审计表保留） */
    @DeleteMapping("/{clientId}")
    public ApiResult<Void> delete(@PathVariable String clientId) {
        clientService.delete(clientId);
        return ApiResult.ok();
    }

    // ---------- 凭证子资源（与 /apps/{appId}/credentials 同形） ----------

    /** 遮显列表（尾 4 指纹，永不回显明文） */
    @GetMapping("/{clientId}/credentials")
    public ApiResult<List<CredentialView>> listCredentials(@PathVariable String clientId) {
        return ApiResult.ok(credentialService.listViews(clientId));
    }

    /** 生成新凭证（`ROTATING` 待激活；明文仅本次回显，交给调用方配置） */
    @PostMapping("/{clientId}/credentials")
    public ApiResult<CredentialIssuedView> prepare(@PathVariable String clientId,
                                                   @Valid @RequestBody PrepareRequest req) {
        return ApiResult.ok(credentialService.prepare(clientId, req));
    }

    /** 激活轮换（旧 `ACTIVE` → `ROTATING` 并存 24h，新 → `ACTIVE`） */
    @PostMapping("/{clientId}/credentials/{id}/activate")
    public ApiResult<Void> activate(@PathVariable String clientId, @PathVariable long id) {
        credentialService.activate(clientId, id);
        return ApiResult.ok();
    }

    /** 一步更新（调用方主动换密钥：新 → `ACTIVE`，旧 → `ROTATING` 并存 24h） */
    @PutMapping("/{clientId}/credentials/{id}")
    public ApiResult<Void> update(@PathVariable String clientId, @PathVariable long id,
                                  @Valid @RequestBody UpdateRequest req) {
        credentialService.update(clientId, req);
        return ApiResult.ok();
    }

    /** 重置（应急：新 → `ACTIVE`，旧全部立即 `RETIRED`） */
    @PostMapping("/{clientId}/credentials/reset")
    public ApiResult<Void> reset(@PathVariable String clientId, @Valid @RequestBody ResetRequest req) {
        credentialService.reset(clientId, req);
        return ApiResult.ok();
    }

    /** 即时失效（泄漏应急；若无剩余 ACTIVE，data 携带告警文案） */
    @PostMapping("/{clientId}/credentials/{id}/retire")
    public ApiResult<String> retire(@PathVariable String clientId, @PathVariable long id) {
        return ApiResult.ok(credentialService.retire(clientId, id));
    }

    /** 完成轮换（`ROTATING` → `RETIRED`） */
    @PostMapping("/{clientId}/credentials/{id}/finish-rotation")
    public ApiResult<Void> finishRotation(@PathVariable String clientId, @PathVariable long id) {
        credentialService.finishRotation(clientId, id);
        return ApiResult.ok();
    }

    /** 删除已失效（`RETIRED`）凭证 */
    @DeleteMapping("/{clientId}/credentials/{id}")
    public ApiResult<Void> deleteCredential(@PathVariable String clientId, @PathVariable long id) {
        credentialService.delete(clientId, id);
        return ApiResult.ok();
    }
}
