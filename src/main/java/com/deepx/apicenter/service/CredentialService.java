package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.dto.CredentialDtos.PrepareRequest;
import com.deepx.apicenter.dto.CredentialDtos.ResetRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.CredentialOwner;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * **应用（供应商）** 凭证管理入口（M0-04）：出站签名 `OUTBOUND` / 回调验签 `CALLBACK`。
 *
 * <p>2026-09-23 重构（入站鉴权 B1 / D-CA-15 方案 A）：**机制已抽到 {@link CredentialStore}**，本类只保留
 * 「应用语义」——即「应用必须存在」这一条检查，其余全部委托。调用方（平台客户）凭证见 {@link ClientCredentialService}。
 * 对外方法签名与语义**完全不变**（M0-04 既有调用点零改动）。
 */
@Service
public class CredentialService {

    private final CredentialStore store;
    private final AppRepository appRepository;

    public CredentialService(CredentialStore store, AppRepository appRepository) {
        this.store = store;
        this.appRepository = appRepository;
    }

    /** 凭证遮显列表（尾 4 指纹；永不回显明文） */
    public List<CredentialView> listViews(String appId) {
        requireApp(appId);
        return store.listViews(CredentialOwner.APP, appId);
    }

    /** 生成新凭证（平台生成随机值）→ `ROTATING` 待激活；明文仅本次回显 */
    public CredentialIssuedView prepare(String appId, PrepareRequest req) {
        requireApp(appId);
        return store.prepare(CredentialOwner.APP, appId, req);
    }

    /** 激活轮换：目标 `ROTATING` → `ACTIVE`，旧 `ACTIVE` → `ROTATING`（并存 +24h） */
    public void activate(String appId, long id) {
        requireApp(appId);
        store.activate(CredentialOwner.APP, appId, id);
    }

    /** 一步更新（供应商主动轮换）：新凭证 → `ACTIVE`，旧 → `ROTATING`（并存 24h） */
    public void update(String appId, UpdateRequest req) {
        requireApp(appId);
        store.update(CredentialOwner.APP, appId, req);
    }

    /** 重置（应急）：新凭证 → `ACTIVE`，旧全部立即 `RETIRED` */
    public void reset(String appId, ResetRequest req) {
        requireApp(appId);
        store.reset(CredentialOwner.APP, appId, req);
    }

    /** 即时失效；若该类型已无 ACTIVE 凭证返回告警文案（引导补发），否则 null */
    public String retire(String appId, long id) {
        requireApp(appId);
        return store.retire(CredentialOwner.APP, appId, id);
    }

    /** 删除已失效（`RETIRED`）凭证 */
    public void delete(String appId, long id) {
        requireApp(appId);
        store.delete(CredentialOwner.APP, appId, id);
    }

    /** 完成轮换：`ROTATING` → `RETIRED` */
    public void finishRotation(String appId, long id) {
        requireApp(appId);
        store.finishRotation(CredentialOwner.APP, appId, id);
    }

    private void requireApp(String appId) {
        if (!appRepository.existsById(appId)) {
            throw BizException.appNotFound(appId);
        }
    }
}
