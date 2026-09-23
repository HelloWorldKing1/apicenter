package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.ClientDtos.ClientDetail;
import com.deepx.apicenter.dto.ClientDtos.ClientRequest;
import com.deepx.apicenter.dto.ClientDtos.ClientResponse;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.ClientAppRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.ClientAppRepository;
import com.deepx.apicenter.repository.CredentialOwner;
import com.deepx.apicenter.repository.CredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 调用方（平台客户 / 接入方）管理 —— 2026-09-23 入站鉴权 **B1「数据与目录」**。
 *
 * <p>范围：CRUD + 启停用 + 删除（级联凭证）+ 列表凭证角标。**不含**鉴权判定（B2/B3 的闸门）。
 *
 * <p>凭证操作走 {@link ClientCredentialService}（与 M0-04 应用凭证**共用机制**，见 {@code CredentialStore}）。
 */
@Service
public class ClientService {

    /** 调用方标识：3~32 位大写字母 / 数字 / `-` / `_`（设计方案 §7.1） */
    private static final Pattern CLIENT_ID = Pattern.compile("^[A-Z0-9_-]{3,32}$");

    private final ClientAppRepository clientAppRepository;
    private final ClientCredentialService clientCredentialService;
    private final CredentialRepository credentialRepository;
    private final AdapterRepository adapterRepository;

    public ClientService(ClientAppRepository clientAppRepository,
                         ClientCredentialService clientCredentialService,
                         CredentialRepository credentialRepository,
                         AdapterRepository adapterRepository) {
        this.clientAppRepository = clientAppRepository;
        this.clientCredentialService = clientCredentialService;
        this.credentialRepository = credentialRepository;
        this.adapterRepository = adapterRepository;
    }

    /** 列表（含凭证角标：已有 ACTIVE 凭证的类型，一次 IN 查询） */
    public List<ClientResponse> list(String keyword, String status) {
        List<ClientAppRow> rows = clientAppRepository.findAll(keyword, status);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> active = credentialRepository.findActiveKinds(CredentialOwner.CLIENT,
                rows.stream().map(ClientAppRow::clientId).toList());
        return rows.stream()
                .map(r -> toResponse(r, active.getOrDefault(r.clientId(), Set.of())))
                .toList();
    }

    /** 详情：调用方 + 凭证遮显列表（永不回显明文） */
    public ClientDetail detail(String clientId) {
        ClientAppRow row = require(clientId);
        Set<String> active = credentialRepository
                .findActiveKinds(CredentialOwner.CLIENT, List.of(clientId))
                .getOrDefault(clientId, Set.of());
        return new ClientDetail(toResponse(row, active), clientCredentialService.listViews(clientId));
    }

    /** 新建：标识格式 + 全库唯一；初始状态 `ENABLED`（未配鉴权方式时启用后 fail-closed 40108，见设计方案 D-CA-7） */
    @Transactional
    public ClientResponse create(ClientRequest req) {
        String clientId = req.clientId() == null ? "" : req.clientId().trim();
        if (!CLIENT_ID.matcher(clientId).matches()) {
            throw BizException.fieldInvalid("调用方标识仅允许 3~32 位大写字母/数字/下划线/中划线");
        }
        if (clientAppRepository.existsById(clientId)) {
            throw BizException.fieldInvalid("调用方标识已存在：" + clientId);
        }
        requireAdapterIfPresent(req.authAdapterId());
        String status = "ENABLED";
        clientAppRepository.insert(new ClientAppRow(null, clientId, req.name().trim(), req.contact(),
                blankToNull(req.authAdapterId()), blankToNull(req.ipWhitelist()), blankToNull(req.ipBlacklist()),
                req.qpsLimit(), req.dailyQuota(), status, req.desc(), null, null));
        return toResponse(require(clientId), Set.of());
    }

    /** 更新（名称 / 联系人 / 鉴权适配器 / IP 名单 / 配额 / 描述；标识不可改） */
    @Transactional
    public ClientResponse update(String clientId, ClientRequest req) {
        ClientAppRow current = require(clientId);
        requireAdapterIfPresent(req.authAdapterId());
        clientAppRepository.update(new ClientAppRow(current.id(), current.clientId(),
                req.name().trim(), req.contact(), blankToNull(req.authAdapterId()),
                blankToNull(req.ipWhitelist()), blankToNull(req.ipBlacklist()),
                req.qpsLimit(), req.dailyQuota(), current.status(), req.desc(), null, null));
        Set<String> active = credentialRepository
                .findActiveKinds(CredentialOwner.CLIENT, List.of(clientId))
                .getOrDefault(clientId, Set.of());
        return toResponse(require(clientId), active);
    }

    /** 启停用（幂等设置；停用即拒 40107） */
    @Transactional
    public void setStatus(String clientId, String status) {
        require(clientId);
        clientAppRepository.updateStatus(clientId, status);
    }

    /** 删除（级联删凭证；**审计表保留** —— 靠主体名称快照回溯） */
    @Transactional
    public void delete(String clientId) {
        require(clientId);
        clientAppRepository.deleteCascade(clientId);
    }

    // ---------- 私有 ----------

    private ClientAppRow require(String clientId) {
        return clientAppRepository.findById(clientId)
                .orElseThrow(() -> BizException.fieldInvalid("调用方不存在：" + clientId));
    }

    /** 鉴权适配器（若填）必须存在（沿用 AppService 的口径） */
    private void requireAdapterIfPresent(String adapterId) {
        if (adapterId != null && !adapterId.isBlank() && !adapterRepository.existsById(adapterId)) {
            throw BizException.fieldInvalid("鉴权适配器不存在：" + adapterId);
        }
    }

    private ClientResponse toResponse(ClientAppRow r, Set<String> activeKinds) {
        List<String> kinds = new ArrayList<>(activeKinds);
        kinds.sort(String::compareTo);
        return new ClientResponse(r.id(), r.clientId(), r.name(), r.contact(), r.authAdapterId(),
                r.ipWhitelist(), r.ipBlacklist(), r.qpsLimit(), r.dailyQuota(),
                r.status(), r.desc(), r.createdAt(), r.updatedAt(), kinds);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
