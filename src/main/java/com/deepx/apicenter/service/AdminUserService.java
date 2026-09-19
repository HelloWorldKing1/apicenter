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
 * 账号管理（v1，2026-09-18）：列表 / 新建 / 编辑（显示名 + 启停用）/ 重置口令 / 解除锁定 / 删除。
 *
 * <p><b>为什么有守卫</b>：v1 **没有角色与权限**（用户明确要求），任何已登录账号都能管理账号。
 * 因此这里只做「不把系统锁死」的安全底线，而不做操作者分级：
 * <ol>
 *   <li>**不能停用/删除最后一个可用账号**（操作后 ENABLED 数不得为 0）——否则谁也登不进；</li>
 *   <li>**不能对自己执行停用/删除/重置口令**——改自己的口令走「修改密码」（需验原密码，语义更清晰）。</li>
 * </ol>
 * 两条守卫的**判定顺序**：先「最后一个可用账号」后「自己」——这样单账号环境下尝试停用自己，
 * 得到的是更贴切的提示。权限分级（RBAC）见《账号登录设计方案.md》§12。
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

    /** 新建账号（不建会话：新账号自己登录；与「注册」共用同一套规则） */
    @Transactional
    public long create(long operatorId, String usernameRaw, String password, String displayName) {
        String username = AccountRules.normalize(usernameRaw);
        AccountRules.validateUsername(username);
        AccountRules.validatePassword(password);
        if (userRepository.findByUsername(username).isPresent()) {
            throw new BizException(BizException.USERNAME_TAKEN, "用户名已存在：" + username);
        }
        long id = userRepository.insert(username, AccountRules.blankToNull(displayName), hasher.hash(password));
        log.info("账号管理：operator={} 新建账号 username={}（id={}）", operatorId, username, id);
        return id;
    }

    /** 编辑：显示名 + 状态。停用会**立即吊销该账号所有会话**（下一次请求即掉线） */
    @Transactional
    public void update(long operatorId, long id, String displayName, String status) {
        AdminUserRow target = require(id);
        String next = status == null || status.isBlank() ? target.status() : status.trim().toUpperCase();
        if (!List.of("ENABLED", "DISABLED").contains(next)) {
            throw BizException.fieldInvalid("状态只能是 ENABLED 或 DISABLED");
        }
        boolean disabling = "ENABLED".equals(target.status()) && "DISABLED".equals(next);
        if (disabling) {
            guardLockout(operatorId, target, "停用");
        }
        userRepository.updateProfile(id, AccountRules.blankToNull(displayName), next);
        if (disabling) {
            int revoked = sessionRepository.deleteByUserId(id);
            log.info("账号管理：operator={} 停用账号 id={}（吊销会话 {} 个）", operatorId, id, revoked);
        } else {
            log.info("账号管理：operator={} 更新账号 id={}（status={}）", operatorId, id, next);
        }
    }

    /** 重置口令：只能由他人执行（自己的口令走「修改密码」需验原密码）；重置即吊销该账号全部会话 */
    @Transactional
    public void resetPassword(long operatorId, long id, String newPassword) {
        AdminUserRow target = require(id);
        if (target.id() == operatorId) {
            throw BizException.fieldInvalid("重置自己的口令请用「修改密码」（需校验原密码）");
        }
        AccountRules.validatePassword(newPassword);
        userRepository.updatePassword(id, hasher.hash(newPassword));
        int revoked = sessionRepository.deleteByUserId(id);
        log.info("账号管理：operator={} 重置账号 id={} 口令（吊销会话 {} 个）", operatorId, id, revoked);
    }

    /** 解除锁定（清失败计数与锁定时间） */
    public void unlock(long operatorId, long id) {
        require(id);
        userRepository.unlock(id);
        log.info("账号管理：operator={} 解锁账号 id={}", operatorId, id);
    }

    /** 删除账号（联删其会话）；不能删自己，也不能删掉最后一个可用账号 */
    @Transactional
    public void delete(long operatorId, long id) {
        AdminUserRow target = require(id);
        guardLockout(operatorId, target, "删除");
        userRepository.delete(id);
        sessionRepository.deleteByUserId(id);   // 干净起见；正常路径下删账号前该账号会话已被守卫/流程清理
        log.warn("账号管理：operator={} 删除账号 username={}（id={}）", operatorId, target.username(), id);
    }

    // ---------- 内部 ----------

    private AdminUserRow require(long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BizException(BizException.ADMIN_USER_NOT_FOUND, "账号不存在：" + id));
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
