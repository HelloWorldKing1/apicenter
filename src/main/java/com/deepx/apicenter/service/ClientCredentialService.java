package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.dto.CredentialDtos.PrepareRequest;
import com.deepx.apicenter.dto.CredentialDtos.ResetRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.repository.ClientAppRepository;
import com.deepx.apicenter.repository.CredentialOwner;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * **调用方（平台客户）** 凭证管理入口（2026-09-23，入站鉴权 B1 / D-CA-15 方案 A）。
 *
 * <p>与 {@link CredentialService}（应用=供应商）**共用同一套机制**（{@link CredentialStore}），
 * 差别只在「属主」：本类校验**调用方必须存在**，凭证类型是 `API_KEY` / `HMAC_SECRET` / `BEARER_TOKEN` / `BASIC`
 * （见 {@link CredentialOwner#CLIENT}）。
 *
 * <p>轮换语义与遮显口径**与 M0-04 完全一致**（这是 E2 修复与评审踩过的地方）：
 * `prepare` 只统计**未过期**的 ROTATING（`countLiveRotating`）、`activate`/`update` 走 CAS 流转防双 ACTIVE、
 * 管理面只回尾 4 指纹、明文仅在 `prepare` 响应出现一次。
 */
@Service
public class ClientCredentialService {

    private final CredentialStore store;
    private final ClientAppRepository clientAppRepository;

    public ClientCredentialService(CredentialStore store, ClientAppRepository clientAppRepository) {
        this.store = store;
        this.clientAppRepository = clientAppRepository;
    }

    /** 调用方凭证遮显列表（尾 4 指纹；永不回显明文） */
    public List<CredentialView> listViews(String clientId) {
        requireClient(clientId);
        return store.listViews(CredentialOwner.CLIENT, clientId);
    }

    /** 生成新凭证 → `ROTATING` 待激活；明文仅本次回显（交付给调用方配置） */
    public CredentialIssuedView prepare(String clientId, PrepareRequest req) {
        requireClient(clientId);
        return store.prepare(CredentialOwner.CLIENT, clientId, req);
    }

    /** 激活轮换：目标 `ROTATING` → `ACTIVE`，旧 `ACTIVE` → `ROTATING`（并存 +24h） */
    public void activate(String clientId, long id) {
        requireClient(clientId);
        store.activate(CredentialOwner.CLIENT, clientId, id);
    }

    /** 一步更新（调用方主动换密钥场景）：新凭证 → `ACTIVE`，旧 → `ROTATING`（并存 24h） */
    public void update(String clientId, UpdateRequest req) {
        requireClient(clientId);
        store.update(CredentialOwner.CLIENT, clientId, req);
    }

    /** 重置（应急）：新凭证 → `ACTIVE`，旧全部立即 `RETIRED` */
    public void reset(String clientId, ResetRequest req) {
        requireClient(clientId);
        store.reset(CredentialOwner.CLIENT, clientId, req);
    }

    /** 即时失效；若该类型已无 ACTIVE 凭证返回告警文案（引导补发），否则 null */
    public String retire(String clientId, long id) {
        requireClient(clientId);
        return store.retire(CredentialOwner.CLIENT, clientId, id);
    }

    /** 删除已失效（`RETIRED`）凭证 */
    public void delete(String clientId, long id) {
        requireClient(clientId);
        store.delete(CredentialOwner.CLIENT, clientId, id);
    }

    /** 完成轮换：`ROTATING` → `RETIRED` */
    public void finishRotation(String clientId, long id) {
        requireClient(clientId);
        store.finishRotation(CredentialOwner.CLIENT, clientId, id);
    }

    private void requireClient(String clientId) {
        if (!clientAppRepository.existsById(clientId)) {
            throw BizException.fieldInvalid("调用方不存在：" + clientId);
        }
    }
}
