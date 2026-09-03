package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.CredentialDtos.CredentialIssuedView;
import com.deepx.apicenter.dto.CredentialDtos.CredentialView;
import com.deepx.apicenter.dto.CredentialDtos.PrepareRequest;
import com.deepx.apicenter.dto.CredentialDtos.ResetRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.CredentialRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.CredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 凭证管理（M0-04 状态机）：ACTIVE 当前使用 / ROTATING 轮换并存（默认 +24h 窗口）/ RETIRED 已失效。
 * 读取规则：出站签名仅用 ACTIVE；回调验签用 ACTIVE + ROTATING 逐个试（findVerifiable，M2 用）。
 * 管理面永不回显明文（仅指纹）；生成新凭证时明文仅回显一次。
 */
@Service
public class CredentialService {

    private static final Set<String> KINDS = Set.of("OUTBOUND", "CALLBACK");

    private final CredentialRepository credentialRepository;
    private final AppRepository appRepository;
    private final CryptoService cryptoService;
    private final SecureRandom random = new SecureRandom();

    public CredentialService(CredentialRepository credentialRepository,
                             AppRepository appRepository,
                             CryptoService cryptoService) {
        this.credentialRepository = credentialRepository;
        this.appRepository = appRepository;
        this.cryptoService = cryptoService;
    }

    /** 凭证遮显列表：指纹 = 明文尾 4 位；expired = ROTATING 且并存窗口已过（惰性失效） */
    public List<CredentialView> listViews(String appId) {
        requireApp(appId);
        LocalDateTime now = LocalDateTime.now();
        return credentialRepository.findByApp(appId).stream()
                .map(r -> {
                    String fingerprint;
                    try {
                        fingerprint = cryptoService.fingerprint(cryptoService.decrypt(r.credential()));
                    } catch (Exception e) {
                        fingerprint = "****"; // 密钥轮换导致旧密文不可解时兜底展示
                    }
                    boolean expired = "ROTATING".equals(r.status())
                            && r.rotatingUntil() != null && r.rotatingUntil().isBefore(now);
                    return new CredentialView(r.id(), r.kind(), r.status(), fingerprint,
                            r.activatedAt(), r.retiredAt(), r.rotatingUntil(), expired);
                })
                .toList();
    }

    /**
     * 生成新凭证（平台生成随机值），status=ROTATING 待激活——出站轮换第一步：
     * 先到供应商侧配置新凭证，确认后调 activate（M0-04 流程①）。
     * 明文仅本次回显，此后不可再读。
     * 方法级 synchronized 为单机并发兜底（防同 (app_id, kind) 双 ROTATING）：
     * 「前置 count 检查 + 插入」非原子，管理面低频操作下串行化即可；
     * 多实例部署时需在库级加唯一约束（M5 生产加固，见 M2代码评审记录 N2）。
     */
    @Transactional
    public synchronized CredentialIssuedView prepare(String appId, PrepareRequest req) {
        requireApp(appId);
        validateKind(req.kind());
        if (credentialRepository.countByStatus(appId, req.kind(), "ROTATING") > 0) {
            throw BizException.fieldInvalid("该类型已有待激活的轮换凭证，请先激活或废弃");
        }
        String plaintext = randomSecret();
        credentialRepository.insert(new CredentialRow(0, appId, req.kind(),
                cryptoService.encrypt(plaintext), "ROTATING", null, null, null, null));
        return new CredentialIssuedView(-1, req.kind(), plaintext);
    }

    /**
     * 激活（M0-04 流程①第二步）：目标 ROTATING → ACTIVE；旧 ACTIVE → ROTATING（并存窗口 +24h）。
     */
    @Transactional
    public void activate(String appId, long id) {
        requireApp(appId);
        CredentialRow target = credentialRepository.findById(id)
                .orElseThrow(() -> BizException.fieldInvalid("凭证不存在：" + id));
        if (!target.appId().equals(appId)) {
            throw BizException.fieldInvalid("凭证不属于该应用");
        }
        if (!"ROTATING".equals(target.status())) {
            throw BizException.fieldInvalid("仅待激活（ROTATING）凭证可激活，当前状态：" + target.status());
        }
        // CAS 式流转（并发防双 ACTIVE）：条件更新判行数，0 = 已被并发变更
        credentialRepository.findActive(appId, target.kind()).ifPresent(old -> {
            int n = credentialRepository.transitionStatus(old.id(), "ACTIVE", "ROTATING", null,
                    LocalDateTime.now().plusHours(24));
            if (n == 0) {
                throw BizException.fieldInvalid("凭证状态已被并发变更，请刷新后重试");
            }
        });
        int n = credentialRepository.transitionStatus(id, "ROTATING", "ACTIVE", null, null);
        if (n == 0) {
            throw BizException.fieldInvalid("凭证状态已被并发变更，请刷新后重试");
        }
    }

    /**
     * 一步更新（M0-04 流程②，供应商主动轮换回调密钥场景）：
     * 管理员录入新凭证 → ACTIVE；旧 ACTIVE → ROTATING（并存 24h 覆盖供应商侧切换窗口）。
     */
    @Transactional
    public void update(String appId, UpdateRequest req) {
        requireApp(appId);
        validateKind(req.kind());
        // CAS 式流转（并发防双 ACTIVE）
        credentialRepository.findActive(appId, req.kind()).ifPresent(old -> {
            int n = credentialRepository.transitionStatus(old.id(), "ACTIVE", "ROTATING", null,
                    LocalDateTime.now().plusHours(24));
            if (n == 0) {
                throw BizException.fieldInvalid("凭证状态已被并发变更，请刷新后重试");
            }
        });
        credentialRepository.insert(new CredentialRow(0, appId, req.kind(),
                cryptoService.encrypt(req.credential()), "ACTIVE", null, null, null, null));
    }

    /**
     * 重置（M0-04 流程③，应急语义）：新凭证 → ACTIVE；旧凭证全部立即 RETIRED，不做并存。
     */
    @Transactional
    public void reset(String appId, ResetRequest req) {
        requireApp(appId);
        validateKind(req.kind());
        credentialRepository.retireAll(appId, req.kind());
        credentialRepository.insert(new CredentialRow(0, appId, req.kind(),
                cryptoService.encrypt(req.credential()), "ACTIVE", null, null, null, null));
    }

    /**
     * 即时失效（M0-04 流程④，泄漏应急）：目标 → RETIRED。
     * 若该类型已无 ACTIVE 凭证，返回告警信息（管理面强制引导补发）。
     */
    @Transactional
    public String retire(String appId, long id) {
        requireApp(appId);
        CredentialRow target = credentialRepository.findById(id)
                .orElseThrow(() -> BizException.fieldInvalid("凭证不存在：" + id));
        if (!target.appId().equals(appId)) {
            throw BizException.fieldInvalid("凭证不属于该应用");
        }
        credentialRepository.updateStatus(id, "RETIRED", LocalDateTime.now(), null);
        if (credentialRepository.countByStatus(appId, target.kind(), "ACTIVE") == 0) {
            return "该类型已无有效凭证，出站签名/回调验签将不可用，请立即补发";
        }
        return null;
    }

    /**
     * 删除已失效凭证（历史清理）：仅 RETIRED 可删除；
     * ACTIVE / ROTATING 受状态机保护，删除前必须先失效。
     */
    @Transactional
    public void delete(String appId, long id) {
        requireApp(appId);
        CredentialRow target = credentialRepository.findById(id)
                .orElseThrow(() -> BizException.fieldInvalid("凭证不存在：" + id));
        if (!target.appId().equals(appId)) {
            throw BizException.fieldInvalid("凭证不属于该应用");
        }
        if (!"RETIRED".equals(target.status())) {
            throw BizException.fieldInvalid("仅已失效（RETIRED）凭证可删除，当前状态：" + target.status());
        }
        credentialRepository.delete(id);
    }

    /** 完成轮换：ROTATING → RETIRED（提前收尾，未到 24h 窗口也可手动完成） */
    @Transactional
    public void finishRotation(String appId, long id) {
        requireApp(appId);
        CredentialRow target = credentialRepository.findById(id)
                .orElseThrow(() -> BizException.fieldInvalid("凭证不存在：" + id));
        if (!target.appId().equals(appId)) {
            throw BizException.fieldInvalid("凭证不属于该应用");
        }
        if (!"ROTATING".equals(target.status())) {
            throw BizException.fieldInvalid("仅轮换并存（ROTATING）凭证可完成轮换，当前状态：" + target.status());
        }
        credentialRepository.updateStatus(id, "RETIRED", LocalDateTime.now(), null);
    }

    // ---------- 私有 ----------

    private void requireApp(String appId) {
        if (!appRepository.existsById(appId)) {
            throw BizException.appNotFound(appId);
        }
    }

    private void validateKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw BizException.fieldInvalid("凭证类型仅支持 OUTBOUND / CALLBACK：" + kind);
        }
    }

    /** 平台生成的随机凭证值（32 字节 hex） */
    private String randomSecret() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
