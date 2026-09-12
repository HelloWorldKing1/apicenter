package com.deepx.apicenter.service;

import com.deepx.apicenter.config.ConfigChangedEvent;
import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.AppDtos.AppResponse;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AppRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.CredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用管理：CRUD + 生命周期状态机（DRAFT → ENABLED → DISABLED → CANCELLED，设计 §1.1）。
 * 「停用即拒请求」钩子：{@link #isRequestAllowed}（M2 接入层调用；M1 以单测验证状态语义）。
 */
@Service
public class AppService {

    private final AppRepository appRepository;
    private final AdapterRepository adapterRepository;
    private final CredentialService credentialService;
    private final CredentialRepository credentialRepository;
    private final com.deepx.apicenter.service.GatewayGuard gatewayGuard;
    private final com.deepx.apicenter.service.AlertService alertService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public AppService(AppRepository appRepository,
                      AdapterRepository adapterRepository,
                      CredentialService credentialService,
                      CredentialRepository credentialRepository,
                      com.deepx.apicenter.service.GatewayGuard gatewayGuard,
                      com.deepx.apicenter.service.AlertService alertService,
                      org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.appRepository = appRepository;
        this.adapterRepository = adapterRepository;
        this.credentialService = credentialService;
        this.credentialRepository = credentialRepository;
        this.gatewayGuard = gatewayGuard;
        this.alertService = alertService;
        this.eventPublisher = eventPublisher;
    }

    public List<AppResponse> list(String keyword) {
        return list(keyword, null);
    }

    public List<AppResponse> list(String keyword, String status) {
        List<AppRow> rows = appRepository.findAll(keyword, status);
        // E1：列表凭证角标（一次 IN 查询，避免逐行查询）
        Map<String, Set<String>> activeKinds = credentialRepository.findActiveKinds(
                rows.stream().map(AppRow::appId).toList());
        return rows.stream()
                .map(r -> AppResponse.from(r, activeKinds.getOrDefault(r.appId(), Set.of())))
                .toList();
    }

    public AppResponse detail(String appId) {
        AppRow row = appRepository.findById(appId).orElseThrow(() -> BizException.appNotFound(appId));
        // 详情附带凭证遮显列表（指纹 + 状态，永不回显明文）
        List<CredentialView> credentials = credentialService.listViews(appId);
        Set<String> activeKinds = credentials.stream()
                .filter(c -> "ACTIVE".equals(c.status()))
                .map(CredentialView::kind)
                .collect(Collectors.toSet());
        return AppResponse.from(row, activeKinds, credentials);
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
                null, appId, req.name(), req.contact(),
                req.authAdapterId(), req.callbackAuthAdapterId(), req.defaultMessageAdapterId(),
                req.baseUrl(), req.ipWhitelist(), req.ipBlacklist(),
                req.qpsLimit(), req.dailyQuota(), current.status(), req.desc(),
                null, null, 0, 0));
        // M5 D-M5-2：默认三绑定 / baseUrl 变更 → APP 事件全清链缓存（含并发在途链的原子性由乐观锁保证）
        eventPublisher.publishEvent(ConfigChangedEvent.appChanged());
    }

    // ---------- 生命周期状态机（设计 §1.1） ----------

    @Transactional
    public void enable(String appId) {
        AppRow row = appRepository.findById(appId).orElseThrow(() -> BizException.appNotFound(appId));
        if (!List.of("DRAFT", "DISABLED").contains(row.status())) {
            throw BizException.fieldInvalid("仅草稿/停用状态可启用，当前状态：" + row.status());
        }
        appRepository.updateStatus(appId, "ENABLED");
        eventPublisher.publishEvent(ConfigChangedEvent.appChanged());
    }

    @Transactional
    public void disable(String appId) {
        AppRow row = appRepository.findById(appId).orElseThrow(() -> BizException.appNotFound(appId));
        if (!"ENABLED".equals(row.status())) {
            throw BizException.fieldInvalid("仅启用状态可停用，当前状态：" + row.status());
        }
        appRepository.updateStatus(appId, "DISABLED");
        eventPublisher.publishEvent(ConfigChangedEvent.appChanged());
    }

    @Transactional
    public void cancel(String appId) {
        AppRow row = appRepository.findById(appId).orElseThrow(() -> BizException.appNotFound(appId));
        if (!"DISABLED".equals(row.status())) {
            throw BizException.fieldInvalid("仅停用状态可注销，当前状态：" + row.status());
        }
        appRepository.updateStatus(appId, "CANCELLED");
        eventPublisher.publishEvent(ConfigChangedEvent.appChanged());
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
        gatewayGuard.evict(appId);          // 内存态清理（2026-09-12）
        alertService.evictApp(appId);
        eventPublisher.publishEvent(ConfigChangedEvent.appChanged());
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
                null, req.appId(), req.name(), req.contact(),
                req.authAdapterId(), req.callbackAuthAdapterId(), req.defaultMessageAdapterId(),
                req.baseUrl(), req.ipWhitelist(), req.ipBlacklist(),
                req.qpsLimit(), req.dailyQuota(), status, req.desc(),
                null, null, 0, 0);
    }
}
