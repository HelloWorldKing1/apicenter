package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.AppDtos.AppResponse;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AppRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AppRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 应用管理：CRUD + 生命周期状态机（DRAFT → ENABLED → DISABLED → CANCELLED，设计 §1.1）。
 * 「停用即拒请求」钩子：{@link #isRequestAllowed}（M2 接入层调用；M1 以单测验证状态语义）。
 */
@Service
public class AppService {

    private final AppRepository appRepository;
    private final AdapterRepository adapterRepository;
    private final CredentialService credentialService;

    public AppService(AppRepository appRepository,
                      AdapterRepository adapterRepository,
                      CredentialService credentialService) {
        this.appRepository = appRepository;
        this.adapterRepository = adapterRepository;
        this.credentialService = credentialService;
    }

    public List<AppResponse> list(String keyword) {
        return list(keyword, null);
    }

    public List<AppResponse> list(String keyword, String status) {
        return appRepository.findAll(keyword, status).stream().map(AppResponse::from).toList();
    }

    public AppResponse detail(String appId) {
        AppRow row = appRepository.findById(appId).orElseThrow(() -> BizException.appNotFound(appId));
        AppResponse base = AppResponse.from(row);
        // 详情附带凭证遮显列表（指纹 + 状态，永不回显明文）
        return new AppResponse(
                base.appId(), base.name(), base.contact(),
                base.authAdapterId(), base.callbackAuthAdapterId(), base.defaultMessageAdapterId(),
                base.baseUrl(), base.ipWhitelist(), base.ipBlacklist(),
                base.qpsLimit(), base.dailyQuota(), base.status(), base.desc(),
                base.createdAt(), base.updatedAt(), base.groupCount(), base.ifaceCount(),
                credentialService.listViews(appId));
    }

    @Transactional
    public void create(AppRequest req) {
        if (!req.appId().matches("[A-Za-z0-9_-]{1,32}")) {
            throw BizException.fieldInvalid("应用标识仅允许字母/数字/下划线/中划线，长度 1~32");
        }
        if (appRepository.existsById(req.appId())) {
            throw BizException.fieldInvalid("应用标识已存在：" + req.appId());
        }
        validateAdapterRefs(req.authAdapterId(), req.callbackAuthAdapterId(), req.defaultMessageAdapterId());
        appRepository.insert(toRow(req, "DRAFT"));
    }

    @Transactional
    public void update(String appId, AppRequest req) {
        AppRow current = appRepository.findById(appId).orElseThrow(() -> BizException.appNotFound(appId));
        validateAdapterRefs(req.authAdapterId(), req.callbackAuthAdapterId(), req.defaultMessageAdapterId());
        // 状态字段不在编辑范围（生命周期走操作端点），沿用当前状态
        appRepository.update(new AppRow(
                appId, req.name(), req.contact(),
                req.authAdapterId(), req.callbackAuthAdapterId(), req.defaultMessageAdapterId(),
                req.baseUrl(), req.ipWhitelist(), req.ipBlacklist(),
                req.qpsLimit(), req.dailyQuota(), current.status(), req.desc(),
                null, null, 0, 0));
    }

    // ---------- 生命周期状态机（设计 §1.1） ----------

    @Transactional
    public void enable(String appId) {
        AppRow row = appRepository.findById(appId).orElseThrow(() -> BizException.appNotFound(appId));
        if (!List.of("DRAFT", "DISABLED").contains(row.status())) {
            throw BizException.fieldInvalid("仅草稿/停用状态可启用，当前状态：" + row.status());
        }
        appRepository.updateStatus(appId, "ENABLED");
    }

    @Transactional
    public void disable(String appId) {
        AppRow row = appRepository.findById(appId).orElseThrow(() -> BizException.appNotFound(appId));
        if (!"ENABLED".equals(row.status())) {
            throw BizException.fieldInvalid("仅启用状态可停用，当前状态：" + row.status());
        }
        appRepository.updateStatus(appId, "DISABLED");
    }

    @Transactional
    public void cancel(String appId) {
        AppRow row = appRepository.findById(appId).orElseThrow(() -> BizException.appNotFound(appId));
        if (!"DISABLED".equals(row.status())) {
            throw BizException.fieldInvalid("仅停用状态可注销，当前状态：" + row.status());
        }
        appRepository.updateStatus(appId, "CANCELLED");
    }

    /** 停用即拒请求（M2 接入层钩子；M1 单测覆盖状态语义） */
    public boolean isRequestAllowed(String appId) {
        return appRepository.isEnabled(appId);
    }

    @Transactional
    public void delete(String appId) {
        appRepository.findById(appId).orElseThrow(() -> BizException.appNotFound(appId));
        if (appRepository.countInterfaces(appId) > 0) {
            throw BizException.fieldInvalid("应用下存在接口，禁止删除（可先将接口下线或移除）");
        }
        appRepository.deleteCascade(appId);
    }

    // ---------- 私有 ----------

    /** 适配器引用存在性校验（引用完整性应用层保证，schema.sql 约定） */
    private void validateAdapterRefs(String... adapterIds) {
        for (String id : adapterIds) {
            if (id != null && !id.isBlank() && !adapterRepository.existsById(id)) {
                throw BizException.fieldInvalid("引用的适配器不存在：" + id);
            }
        }
    }

    private AppRow toRow(AppRequest req, String status) {
        return new AppRow(
                req.appId(), req.name(), req.contact(),
                req.authAdapterId(), req.callbackAuthAdapterId(), req.defaultMessageAdapterId(),
                req.baseUrl(), req.ipWhitelist(), req.ipBlacklist(),
                req.qpsLimit(), req.dailyQuota(), status, req.desc(),
                null, null, 0, 0);
    }
}
