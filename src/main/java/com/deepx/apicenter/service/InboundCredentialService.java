package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.model.ClientAppRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.dto.CredentialDtos.PrepareRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.repository.ClientAppRepository;
import com.deepx.apicenter.repository.CredentialOwner;
import com.deepx.apicenter.repository.InterfaceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/**
 * **入站鉴权凭证池**服务（2026-09-24 v1.2 / D-CA-18）—— 三级属主的统一入口。
 *
 * <p><b>为什么需要它</b>：调用方是**未知且持续新增的第三方**（开放集）⇒ 若每个调用方都必须先建档
 * （`client_app`）才能鉴权，接入成本 = 每次运维介入；凭证池把接入退化为「**发一把密钥**」（1 步），
 * 同时因「一凭证一行 + `label`」保留**单独吊销**与**按标签归因**。
 *
 * <p><b>三级属主与池取值</b>（判定在闸门，见设计方案 v1.2 §6.1）：
 * <ul>
 *   <li>{@code INTERFACE}：接口专属（`owner_id` = 接口数字 id）—— 本接口只认这些密钥；</li>
 *   <li>{@code PLATFORM}：平台共享（`owner_id` = NULL）—— 未绑定接口的默认池；</li>
 *   <li>{@code CLIENT}：档案专属（`owner_id` = client_id）—— 需要「可验证身份 / 单独配额」时才登记。</li>
 * </ul>
 * 闸门按 **INTERFACE → PLATFORM → CLIENT 逐级短路**（某级有可用凭证就只用这一级）。
 *
 * <p><b>与 {@code ClientCredentialService} 的关系</b>：后者是「调用方档案」语义入口（B1 落地，保留）；
 * 本类把它包含在内（`CLIENT` 属主走同一套存在性校验），并新增接口/平台两级 ——
 * 管理面 `/clients/{clientId}/credentials` 继续可用（等价于 `ownerType=CLIENT`）。
 */
@Service
public class InboundCredentialService {

    private final CredentialStore store;
    private final ClientAppRepository clientAppRepository;
    private final InterfaceRepository interfaceRepository;

    public InboundCredentialService(CredentialStore store,
                                    ClientAppRepository clientAppRepository,
                                    InterfaceRepository interfaceRepository) {
        this.store = store;
        this.clientAppRepository = clientAppRepository;
        this.interfaceRepository = interfaceRepository;
    }

    /** 凭证池遮显列表（尾 4 指纹 + 备注；永不回显明文） */
    /**
     * 凭证池列表：**`ownerId` 可留空 = 列出该属主类型的全部**（2026-09-24 使用反馈）。
     *
     * <p>为什么：接口专属池天然是"多个接口各有一份"，`ⓐ 一次只能查一个接口` 既看不全、又要先手工去别处查 id。
     * 现在默认**跨接口一次列出**，并**按行**带上「接口 ID + 接口名称」（档案池同理带上调用方）。
     * 传了 `ownerId` 则退化为"只看这一个属主"（兼容原有用法）。
     */
    public List<CredentialView> list(String ownerType, String ownerId) {
        CredentialOwner owner = resolveOwner(ownerType);
        String normalized = normalizeOwnerId(owner, ownerId);
        if (normalized != null) {
            requireOwnerExists(owner, normalized);
            String ownerName = resolveOwnerName(owner, normalized);
            return store.listViews(owner, normalized).stream()
                    .map(v -> new CredentialView(v.id(), v.kind(), v.status(), v.fingerprint(),
                            v.activatedAt(), v.retiredAt(), v.rotatingUntil(), v.expired(), v.label(),
                            v.ownerType(), v.ownerId(), ownerName))
                    .toList();
        }
        // ownerId 留空 ⇒ **列出该属主类型的全部**（接口池默认跨接口列出；档案池同理）
        List<CredentialView> all = store.listViewsAll(owner);
        Map<String, String> names = resolveOwnerNames(owner, all.stream()
                .map(CredentialView::ownerId).filter(java.util.Objects::nonNull).distinct().toList());
        return all.stream()
                .map(v -> new CredentialView(v.id(), v.kind(), v.status(), v.fingerprint(),
                        v.activatedAt(), v.retiredAt(), v.rotatingUntil(), v.expired(), v.label(),
                        v.ownerType(), v.ownerId(), ownerNameOf(owner, v.ownerId(), names)))
                .toList();
    }

    /** 属主展示名（按行）：平台池固定文案；接口/调用方取批量查到的名字，缺失时给出可读提示 */
    private String ownerNameOf(CredentialOwner owner, String ownerId, Map<String, String> names) {
        return switch (owner) {
            case PLATFORM -> "平台共享池";
            case INTERFACE -> names.getOrDefault(ownerId, "（接口已删除：id=" + ownerId + "）");
            case CLIENT -> names.getOrDefault(ownerId, "（调用方已删除：" + ownerId + "）");
            default -> null;
        };
    }

    /** 单属主路径的展示名 */
    private String resolveOwnerName(CredentialOwner owner, String ownerId) {
        return ownerNameOf(owner, ownerId, resolveOwnerNames(owner, List.of(ownerId)));
    }

    /** 批量解析属主名称（接口池 → 接口名；档案池 → 调用方名）：一次 IN 查询，避免 N+1 */
    private Map<String, String> resolveOwnerNames(CredentialOwner owner, List<String> ownerIds) {
        return switch (owner) {
            case INTERFACE -> interfaceRepository
                    .findNamesByIds(ownerIds.stream().map(Long::parseLong).toList())
                    .entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()), Map.Entry::getValue));
            case CLIENT -> clientAppRepository.findNamesByIds(ownerIds);
            default -> Map.of();
        };
    }

    /** 新增 / 轮换（平台生成随机值）：入 `ROTATING` 待激活，明文仅响应回显一次 */
    public CredentialIssuedView prepare(String ownerType, String ownerId, String kind, String label) {
        CredentialOwner owner = resolveOwner(ownerType);
        String normalized = normalizeOwnerId(owner, ownerId);
        requireOwnerExists(owner, normalized);
        return store.prepare(owner, normalized, new PrepareRequest(kind), label);
    }

    /** 录入（第三方给的密钥）：一步入 `ACTIVE`，旧 `ACTIVE` 转入 `ROTATING`（并存 24h） */
    public void update(String ownerType, String ownerId, String kind, String credential, String label) {
        CredentialOwner owner = resolveOwner(ownerType);
        String normalized = normalizeOwnerId(owner, ownerId);
        requireOwnerExists(owner, normalized);
        store.update(owner, normalized, new UpdateRequest(kind, credential), label);
    }

    /** 仅改备注（不改值、不流转状态）：给「这把密钥是谁的」补上说明 */
    public void updateLabel(String ownerType, String ownerId, long id, String label) {
        CredentialOwner owner = resolveOwner(ownerType);
        String normalized = normalizeOwnerId(owner, ownerId);
        requireOwnerExists(owner, normalized);
        store.updateLabel(owner, normalized, id, label);
    }

    public void activate(String ownerType, String ownerId, long id) {
        CredentialOwner owner = resolveOwner(ownerType);
        store.activate(owner, normalizeOwnerId(owner, ownerId), id);
    }

    /** 即时失效（**单独吊销某个调用方的正式手段**）；该类型已无 ACTIVE 时返回告警文案 */
    public String retire(String ownerType, String ownerId, long id) {
        CredentialOwner owner = resolveOwner(ownerType);
        return store.retire(owner, normalizeOwnerId(owner, ownerId), id);
    }

    /** 完成轮换：`ROTATING` → `RETIRED`（提前收尾，未到 24h 窗口也可手动结束） */
    public void finishRotation(String ownerType, String ownerId, long id) {
        CredentialOwner owner = resolveOwner(ownerType);
        store.finishRotation(owner, normalizeOwnerId(owner, ownerId), id);
    }

    /** 删除已失效凭证（仅 `RETIRED` 可删，状态机保护） */
    public void delete(String ownerType, String ownerId, long id) {
        CredentialOwner owner = resolveOwner(ownerType);
        store.delete(owner, normalizeOwnerId(owner, ownerId), id);
    }

    // ---------- 私有 ----------

    /** 解析属主类型（仅三级池属主；**应用凭证走 `/apps/{appId}/credentials`**，不在此列） */
    private CredentialOwner resolveOwner(String ownerType) {
        if (ownerType == null || ownerType.isBlank()) {
            throw BizException.fieldInvalid("属主类型不能为空（可选：PLATFORM / INTERFACE / CLIENT）");
        }
        CredentialOwner owner;
        try {
            owner = CredentialOwner.valueOf(ownerType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.fieldInvalid("非法属主类型：" + ownerType + "（可选：PLATFORM / INTERFACE / CLIENT）");
        }
        if (!owner.pooled()) {
            throw BizException.fieldInvalid("该属主不属于入站凭证池：" + ownerType);
        }
        return owner;
    }

    /** 归一 `ownerId`：PLATFORM 必须为空（平台共享池的 owner_id 在库里就是 NULL） */
    private String normalizeOwnerId(CredentialOwner owner, String ownerId) {
        String id = ownerId == null || ownerId.isBlank() ? null : ownerId.trim();
        if (owner.nullOwnerId()) {
            if (id != null) {
                throw BizException.fieldInvalid("平台共享池（PLATFORM）不接受 ownerId，请留空");
            }
            return null;
        }
        // 留空 = 列出该属主类型的全部（接口池默认跨接口列出；档案池同理）—— 见 list() 的说明
        return id;
    }

    /** 属主存在性（引用完整性应用层保证）：档案须存在、接口须存在；平台无需 */
    private void requireOwnerExists(CredentialOwner owner, String ownerId) {
        if (ownerId == null) {
            return;   // 列全部：无具体属主可校验
        }
        switch (owner) {
            case CLIENT -> {
                if (!clientAppRepository.existsById(ownerId)) {
                    throw BizException.fieldInvalid("调用方不存在：" + ownerId);
                }
            }
            case INTERFACE -> {
                long interfaceId;
                try {
                    interfaceId = Long.parseLong(ownerId);
                } catch (NumberFormatException e) {
                    throw BizException.fieldInvalid("接口池的 ownerId 必须是接口数字 id：" + ownerId);
                }
                interfaceRepository.findById(interfaceId)
                        .orElseThrow(() -> BizException.fieldInvalid("接口不存在：" + ownerId));
            }
            default -> {
                // PLATFORM：无属主实体可校验
            }
        }
    }
}
