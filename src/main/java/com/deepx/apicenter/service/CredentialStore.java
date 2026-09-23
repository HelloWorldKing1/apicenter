package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.dto.CredentialDtos.PrepareRequest;
import com.deepx.apicenter.dto.CredentialDtos.ResetRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.CredentialRow;
import com.deepx.apicenter.repository.CredentialOwner;
import com.deepx.apicenter.repository.CredentialRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 凭证**机制**（M0-04 状态机），两类属主共用（2026-09-23，入站鉴权 B1 / D-CA-15 方案 A）。
 *
 * <p>从原 `CredentialService` 抽出**与属主无关**的全部机制：`ACTIVE`/`ROTATING`/`RETIRED` 流转、
 * AES-GCM 加解密、遮显（尾 4 指纹）、`prepare` 的 `synchronized` 兜底、`countLiveRotating` 的 E2 语义、
 * 以及「仅 RETIRED 可删」等守卫。
 *
 * <p>**属主语义不在这里**：`CredentialService`（应用=供应商）与 `ClientCredentialService`（调用方=平台客户）
 * 各自做「属主是否存在」检查，再委托本类 —— 保证 `appId` 语义不漂移到调用方（D-CA-1 / §7.1）。
 */
@Component
public class CredentialStore {

    private final CredentialRepository credentialRepository;
    private final CryptoService cryptoService;
    private final SecureRandom random = new SecureRandom();

    public CredentialStore(CredentialRepository credentialRepository, CryptoService cryptoService) {
        this.credentialRepository = credentialRepository;
        this.cryptoService = cryptoService;
    }

    /** 凭证遮显列表：指纹 = 明文尾 4 位；expired = ROTATING 且并存窗口已过（惰性失效） */
    public List<CredentialView> listViews(CredentialOwner owner, String ownerId) {
        LocalDateTime now = LocalDateTime.now();
        return credentialRepository.findByOwner(owner, ownerId).stream()
                .map(r -> new CredentialView(r.id(), r.kind(), r.status(), fingerprintOf(r.credential()),
                        r.activatedAt(), r.retiredAt(), r.rotatingUntil(),
                        "ROTATING".equals(r.status()) && r.rotatingUntil() != null && r.rotatingUntil().isBefore(now)))
                .toList();
    }

    /**
     * 生成新凭证（平台生成随机值），`status=ROTATING` 待激活（M0-04 流程①）：
     * 先到对端配置新凭证，确认后调 {@link #activate}。明文仅本次回显，此后不可再读。
     *
     * <p>方法级 `synchronized` 为单机并发兜底（防同 (属主, kind) 双 ROTATING）：「前置 count 检查 + 插入」非原子，
     * 管理面低频操作下串行化即可；多实例部署需库级唯一约束（M5 生产加固，PolarDB 5.7 不支持函数索引 ⇒ 应用层保证）。
     */
    @Transactional
    public synchronized CredentialIssuedView prepare(CredentialOwner owner, String ownerId, PrepareRequest req) {
        validateKind(owner, req.kind());
        // E2（2026-09-11）：只统计未过期的 ROTATING——过期行读取路径已惰性视为 RETIRED，不应再阻塞新轮换
        if (credentialRepository.countLiveRotating(owner, ownerId, req.kind()) > 0) {
            throw BizException.fieldInvalid("该类型已有待激活的轮换凭证，请先激活或废弃");
        }
        String plaintext = randomSecret();
        long id = credentialRepository.insertAndReturnId(owner, new CredentialRow(0, ownerId, req.kind(),
                cryptoService.encrypt(plaintext), "ROTATING", null, null, null, null));
        return new CredentialIssuedView(id, req.kind(), plaintext);
    }

    /** 激活（M0-04 流程①第二步）：目标 ROTATING → ACTIVE；旧 ACTIVE → ROTATING（并存窗口 +24h） */
    @Transactional
    public void activate(CredentialOwner owner, String ownerId, long id) {
        CredentialRow target = requireOwned(owner, ownerId, id);
        if (!"ROTATING".equals(target.status())) {
            throw BizException.fieldInvalid("仅待激活（ROTATING）凭证可激活，当前状态：" + target.status());
        }
        // CAS 式流转（并发防双 ACTIVE）：条件更新判行数，0 = 已被并发变更
        credentialRepository.findActive(owner, ownerId, target.kind()).ifPresent(old -> {
            int n = credentialRepository.transitionStatus(owner, old.id(), "ACTIVE", "ROTATING", null,
                    LocalDateTime.now().plusHours(24));
            if (n == 0) {
                throw BizException.fieldInvalid("凭证状态已被并发变更，请刷新后重试");
            }
        });
        if (credentialRepository.transitionStatus(owner, id, "ROTATING", "ACTIVE", null, null) == 0) {
            throw BizException.fieldInvalid("凭证状态已被并发变更，请刷新后重试");
        }
    }

    /** 一步更新（M0-04 流程②，对端主动轮换场景）：录入新凭证 → ACTIVE；旧 ACTIVE → ROTATING（并存 24h） */
    @Transactional
    public void update(CredentialOwner owner, String ownerId, UpdateRequest req) {
        validateKind(owner, req.kind());
        credentialRepository.findActive(owner, ownerId, req.kind()).ifPresent(old -> {
            int n = credentialRepository.transitionStatus(owner, old.id(), "ACTIVE", "ROTATING", null,
                    LocalDateTime.now().plusHours(24));
            if (n == 0) {
                throw BizException.fieldInvalid("凭证状态已被并发变更，请刷新后重试");
            }
        });
        credentialRepository.insert(owner, new CredentialRow(0, ownerId, req.kind(),
                cryptoService.encrypt(req.credential()), "ACTIVE", null, null, null, null));
    }

    /** 重置（M0-04 流程③，应急语义）：新凭证 → ACTIVE；旧凭证全部立即 RETIRED，不做并存 */
    @Transactional
    public void reset(CredentialOwner owner, String ownerId, ResetRequest req) {
        validateKind(owner, req.kind());
        credentialRepository.retireAll(owner, ownerId, req.kind());
        credentialRepository.insert(owner, new CredentialRow(0, ownerId, req.kind(),
                cryptoService.encrypt(req.credential()), "ACTIVE", null, null, null, null));
    }

    /**
     * 即时失效（M0-04 流程④，泄漏应急）：目标 → RETIRED。
     * 若该类型已无 ACTIVE 凭证，返回**告警文案**（管理面强制引导补发）；否则返回 null。
     */
    @Transactional
    public String retire(CredentialOwner owner, String ownerId, long id) {
        CredentialRow target = requireOwned(owner, ownerId, id);
        credentialRepository.updateStatus(owner, id, "RETIRED", LocalDateTime.now(), null);
        if (credentialRepository.countActive(owner, ownerId, target.kind()) == 0) {
            return owner.retireWarning();
        }
        return null;
    }

    /** 删除已失效凭证（历史清理）：仅 RETIRED 可删除；ACTIVE / ROTATING 受状态机保护 */
    @Transactional
    public void delete(CredentialOwner owner, String ownerId, long id) {
        CredentialRow target = requireOwned(owner, ownerId, id);
        if (!"RETIRED".equals(target.status())) {
            throw BizException.fieldInvalid("仅已失效（RETIRED）凭证可删除，当前状态：" + target.status());
        }
        credentialRepository.delete(owner, id);
    }

    /** 完成轮换：ROTATING → RETIRED（提前收尾，未到 24h 窗口也可手动完成） */
    @Transactional
    public void finishRotation(CredentialOwner owner, String ownerId, long id) {
        CredentialRow target = requireOwned(owner, ownerId, id);
        if (!"ROTATING".equals(target.status())) {
            throw BizException.fieldInvalid("仅轮换并存（ROTATING）凭证可完成轮换，当前状态：" + target.status());
        }
        credentialRepository.updateStatus(owner, id, "RETIRED", LocalDateTime.now(), null);
    }

    // ---------- 私有 ----------

    /** 取凭证并校验「属于该属主」（不存在 / 不属于 → 40001，文案带主体名） */
    private CredentialRow requireOwned(CredentialOwner owner, String ownerId, long id) {
        CredentialRow target = credentialRepository.findById(owner, id)
                .orElseThrow(() -> BizException.fieldInvalid("凭证不存在：" + id));
        if (!ownerId.equals(target.ownerId())) {
            throw BizException.fieldInvalid("凭证不属于该" + owner.displayName());
        }
        return target;
    }

    /** 类型白名单按属主区分（应用：OUTBOUND/CALLBACK；调用方：API_KEY/HMAC_SECRET/BEARER_TOKEN/BASIC） */
    private void validateKind(CredentialOwner owner, String kind) {
        if (!owner.supportsKind(kind)) {
            throw BizException.fieldInvalid("凭证类型仅支持 " + owner.kindsLabel() + "：" + kind);
        }
    }

    /** 指纹 = 明文尾 4 位；密钥轮换导致旧密文不可解时兜底展示 `****` */
    private String fingerprintOf(String encrypted) {
        try {
            return cryptoService.fingerprint(cryptoService.decrypt(encrypted));
        } catch (Exception e) {
            return "****";
        }
    }

    /** 平台生成的随机凭证值（32 字节 hex） */
    private String randomSecret() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
