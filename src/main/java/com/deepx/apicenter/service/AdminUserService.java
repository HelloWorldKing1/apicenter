package com.deepx.apicenter.service;

import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdminUserRow;
import com.deepx.apicenter.repository.AdminSessionRepository;
import com.deepx.apicenter.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 账号管理（2026-09-18）：列表 / 新建 / 编辑（显示名 + 启停用 + 角色）/ 重置口令 / 解除锁定 / 删除。
 *
 * <p><b>权限模型（RBAC 第一层，{@link RoleRules}）</b>：VIEWER 只读（连入口都不可达，由过滤器拦 40303）；
 * ADMIN 可管理**低于自己等级**的账号（新建 / 启停用 / 重置口令 / 解锁 / 改显示名），但**不能删除账号、不能变更角色**；
 * OWNER 拥有全部能力。端点级强制在 {@code AdminAuthFilter}，本节负责**语义级**校验（越权与锁死防护）。
 *
 * <p><b>不把系统锁死的三条底线</b>（在角色模型上扩展）：
 * <ol>
 *   <li>不能停用/删除**最后一个可用账号**（否则谁也登不进）；</li>
 *   <li>不能停用/删除/重置**自己**（判定顺序固定「先①后②」，单账号环境提示更贴切）；</li>
 *   <li>不能降级/停用/删除**最后一个 OWNER**，也不能**改自己的角色**（后者会导致自锁）。</li>
 * </ol>
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final AdminUserRepository userRepository;
    private final AdminSessionRepository sessionRepository;
    private final PasswordHasher hasher;

    public AdminUserService(AdminUserRepository userRepository, AdminSessionRepository sessionRepository,
                            PasswordHasher hasher) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.hasher = hasher;
    }

    public List<AdminUserRow.ListRow> list(String keyword) {
        return userRepository.findAll(keyword);
    }

    /** 新建账号（不建会话：新账号自己登录；与「注册」共用同一套账号规则） */
    @Transactional
    public long create(long operatorId, String operatorRole, String usernameRaw, String password,
                       String displayName, String role) {
        AccountRules.validateUsername(AccountRules.normalize(usernameRaw));
        String username = AccountRules.normalize(usernameRaw);
        AccountRules.validatePassword(password);
        String next = normalizeRole(role);
        if (!RoleRules.canChangeRole(operatorRole) && !RoleRules.VIEWER.equals(next)) {
            // ADMIN 只能建 VIEWER（不能把别人提升到与自己同级或更高）
            throw new BizException(BizException.NO_ACCOUNT_ADMIN,
                    "当前角色（" + operatorRole + "）只能新建只读（VIEWER）账号，不能分配 " + next);
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new BizException(BizException.USERNAME_TAKEN, "用户名已存在：" + username);
        }
        long id = userRepository.insert(username, AccountRules.blankToNull(displayName), hasher.hash(password), next);
        log.info("账号管理：operator={}({}) 新建账号 username={}（id={} role={}）",
                operatorId, operatorRole, username, id, next);
        return id;
    }

    /**
     * 编辑：显示名 + 状态 + 角色。
     * 停用会**立即吊销该账号所有会话**；角色变更仅 OWNER 可做（且不能改自己、不能降级最后一个 OWNER）。
     */
    @Transactional
    public void update(long operatorId, String operatorRole, long id, String displayName, String status, String role) {
        AdminUserRow target = require(id);
        requireOperable(operatorRole, target);
        String nextStatus = status == null || status.isBlank() ? target.status() : status.trim().toUpperCase();
        if (!List.of("ENABLED", "DISABLED").contains(nextStatus)) {
            throw BizException.fieldInvalid("状态只能是 ENABLED 或 DISABLED");
        }
        String nextRole = role == null || role.isBlank() ? target.role() : normalizeRole(role);
        boolean disabling = "ENABLED".equals(target.status()) && "DISABLED".equals(nextStatus);
        boolean demoting = RoleRules.OWNER.equals(target.role()) && !RoleRules.OWNER.equals(nextRole);

        if (disabling) {
            guardLockout(operatorId, target, "停用");
        }
        if (!nextRole.equals(target.role())) {
            if (!RoleRules.canChangeRole(operatorRole)) {
                throw new BizException(BizException.NO_ACCOUNT_ADMIN,
                        "变更角色需要 OWNER 角色（当前：" + operatorRole + "）");
            }
            if (target.id() == operatorId) {
                throw BizException.fieldInvalid("不能修改自己的角色（请让其他 OWNER 操作）");
            }
            if (demoting && userRepository.countOwners() <= 1) {
                throw BizException.fieldInvalid("这是最后一个 OWNER，不能降级（否则将无人能管理账号与分配角色）");
            }
        }
        userRepository.updateProfile(id, AccountRules.blankToNull(displayName), nextStatus);
        if (!nextRole.equals(target.role())) {
            userRepository.updateRole(id, nextRole);
            sessionRepository.deleteByUserId(id);   // 角色变了 → 让既有会话失效，避免旧权限残留
        }
        if (disabling) {
            int revoked = sessionRepository.deleteByUserId(id);
            log.info("账号管理：operator={}({}) 停用账号 id={}（吊销会话 {} 个）", operatorId, operatorRole, id, revoked);
        } else {
            log.info("账号管理：operator={}({}) 更新账号 id={}（status={} role={}）",
                    operatorId, operatorRole, id, nextStatus, nextRole);
        }
    }

    /** 重置口令：只能重置**等级低于自己**的账号（ADMIN 不能重置 OWNER）；重置即吊销其全部会话 */
    @Transactional
    public void resetPassword(long operatorId, String operatorRole, long id, String newPassword) {
        AdminUserRow target = require(id);
        requireOperable(operatorRole, target);
        if (target.id() == operatorId) {
            throw BizException.fieldInvalid("重置自己的口令请用「修改密码」（需校验原密码）");
        }
        AccountRules.validatePassword(newPassword);
        userRepository.updatePassword(id, hasher.hash(newPassword));
        int revoked = sessionRepository.deleteByUserId(id);
        log.info("账号管理：operator={}({}) 重置账号 id={} 口令（吊销会话 {} 个）",
                operatorId, operatorRole, id, revoked);
    }

    /** 解除锁定（清失败计数与锁定时间） */
    public void unlock(long operatorId, String operatorRole, long id) {
        AdminUserRow target = require(id);
        requireOperable(operatorRole, target);
        userRepository.unlock(id);
        log.info("账号管理：operator={}({}) 解锁账号 id={}", operatorId, operatorRole, id);
    }

    /** 删除账号（联删其会话）：仅 OWNER；不能删自己、不能删最后一个可用账号 */
    @Transactional
    public void delete(long operatorId, String operatorRole, long id) {
        AdminUserRow target = require(id);
        if (!RoleRules.canDeleteAccount(operatorRole)) {
            throw new BizException(BizException.NO_ACCOUNT_ADMIN,
                    "删除账号需要 OWNER 角色（当前：" + operatorRole + "）");
        }
        requireOperable(operatorRole, target);
        guardLockout(operatorId, target, "删除");
        if (RoleRules.OWNER.equals(target.role()) && userRepository.countOwners() <= 1) {
            throw BizException.fieldInvalid("这是最后一个 OWNER，不能删除（否则将无人能管理账号与分配角色）");
        }
        userRepository.delete(id);
        sessionRepository.deleteByUserId(id);
        log.warn("账号管理：operator={}({}) 删除账号 username={}（id={} role={}）",
                operatorId, operatorRole, target.username(), id, target.role());
    }

    // ---------- 内部 ----------

    private AdminUserRow require(long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BizException(BizException.ADMIN_USER_NOT_FOUND, "账号不存在：" + id));
    }

    private String normalizeRole(String role) {
        String next = AccountRules.blankToNull(role);
        if (next == null) {
            return RoleRules.VIEWER;   // 未指定 = 最小权限
        }
        next = next.toUpperCase();
        if (!RoleRules.isKnown(next)) {
            throw BizException.fieldInvalid("角色只能是 " + String.join(" / ", RoleRules.ALL));
        }
        return next;
    }

    /** 不能操作等级 ≥ 自己的账号（OWNER 例外：OWNER 可操作任何账号） */
    private void requireOperable(String operatorRole, AdminUserRow target) {
        if (!RoleRules.canOperate(operatorRole, target.role())) {
            throw new BizException(BizException.NO_ACCOUNT_ADMIN,
                    "当前角色（" + operatorRole + "）不能操作 " + target.role() + " 账号：" + target.username());
        }
    }

    /**
     * 安全底线：① 不能动最后一个可用账号 ② 不能动自己（顺序固定，提示更贴切）。
     *
     * <p><b>调用约定（踩过坑）</b>：第一个参数是**操作者** id，第二个是**被操作账号**——两者都是 `long`，
     * 极易传反；实测把 target id 当 operatorId 传进去会变成「永远拒绝」（2026-09-18 被
     * `AdminUserServiceTest` 的「停用他人」用例抓到）。改这里时务必连带复核审计日志里的 operator。
     */
    private void guardLockout(long operatorId, AdminUserRow target, String action) {
        boolean enabled = "ENABLED".equals(target.status());
        if (enabled && userRepository.countEnabled() <= 1) {
            throw BizException.fieldInvalid("这是最后一个可用账号，不能" + action
                    + "（否则将无人能登录；请先启用/新建另一个账号）");
        }
        if (target.id() == operatorId) {
            throw BizException.fieldInvalid("不能" + action + "当前登录的账号");
        }
    }
}
