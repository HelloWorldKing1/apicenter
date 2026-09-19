package com.deepx.apicenter.service;

import com.deepx.apicenter.config.AuthProperties;
import com.deepx.apicenter.dto.AuthDtos.AuthStatusView;
import com.deepx.apicenter.dto.AuthDtos.LoginView;
import com.deepx.apicenter.dto.AuthDtos.UserView;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdminUserRow;
import com.deepx.apicenter.repository.AdminSessionRepository;
import com.deepx.apicenter.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 管理面账号服务（2026-09-18）：登录 / 注册 / 退出 / 改密 + 令牌校验。
 *
 * <p><b>范围</b>：只做**认证**，不做权限（v1 无角色/资源授权）；保护对象 = `/api/admin/**`。
 * 平台对外接口路径（`/{platformPath}`）**不在**本特性范围——那是「调用方鉴权」（设计 §5.3）。
 *
 * <p><b>安全口径</b>：
 * <ul>
 *   <li>口令 PBKDF2-HMAC-SHA256（{@link PasswordHasher}），库内不可逆；登录失败文案**不区分**「用户不存在/密码错」（防枚举）；</li>
 *   <li>令牌 = 32 字节随机（Base64URL），入库只存 SHA-256 摘要；TTL 见配置，**改密吊销其他会话、登出即时删行**；</li>
 *   <li>暴力破解：连续失败达阈值锁定 N 分钟（成功登录清零），锁定期间**即使密码正确**也拒绝。</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(AuthProperties.class)
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 用户名：3-32 位，字母/数字/下划线/点/连字符（统一小写存储，避免大小写混淆） */
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9_.-]{2,31}$");
    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 64;
    private static final String BAD_CREDENTIALS_MSG = "用户名或密码错误";

    private final AdminUserRepository userRepository;
    private final AdminSessionRepository sessionRepository;
    private final PasswordHasher hasher;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    public AuthService(AdminUserRepository userRepository, AdminSessionRepository sessionRepository,
                       PasswordHasher hasher, AuthProperties props) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.hasher = hasher;
        this.props = props;
    }

    // ---------- 登录 / 注册 / 退出 ----------

    /**
     * 登录。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>（2026-09-18 实测踩坑）：登录失败会抛 {@code BizException}，
     * 若整个方法在同一事务里，**失败计数写入会被回滚** → 连续失败次数永远是 1，锁定形同虚设
     * （用例 `连续失败达阈值_锁定且正确密码也被拒` 抓到的就是这个）。此处各写入彼此独立、
     * 半途失败无副作用，逐条自动提交才是正确语义。
     */
    public LoginView login(String usernameRaw, String password, String clientInfo) {
        String username = normalize(usernameRaw);
        AdminUserRow user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            // 防枚举：与密码错误同一文案（耗时差异不进关键路径，不做恒定时间占位）
            log.warn("管理面登录失败（账号不存在）：username={}", username);
            throw new BizException(BizException.BAD_CREDENTIALS, BAD_CREDENTIALS_MSG);
        }
        if (!"ENABLED".equals(user.status())) {
            throw new BizException(BizException.BAD_CREDENTIALS, "账号已停用，请联系管理员");
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(LocalDateTime.now())) {
            long minutes = Math.max(1, Duration.between(LocalDateTime.now(), user.lockedUntil()).toMinutes() + 1);
            throw new BizException(BizException.ACCOUNT_LOCKED,
                    "账号已锁定，请 " + minutes + " 分钟后重试");
        }
        if (!hasher.verify(password, user.passwordHash())) {
            int failed = user.failedAttempts() + 1;
            int max = props.maxFailedAttemptsOrDefault();
            LocalDateTime lockUntil = failed >= max ? LocalDateTime.now().plusMinutes(props.lockMinutesOrDefault()) : null;
            userRepository.recordLoginFailure(user.id(), failed, lockUntil);
            log.warn("管理面登录失败：username={} 第 {} 次", username, failed);
            if (lockUntil != null) {
                throw new BizException(BizException.ACCOUNT_LOCKED,
                        "连续失败 " + failed + " 次，账号锁定 " + props.lockMinutesOrDefault() + " 分钟");
            }
            throw new BizException(BizException.BAD_CREDENTIALS,
                    BAD_CREDENTIALS_MSG + "（还可尝试 " + (max - failed) + " 次）");
        }
        userRepository.recordLoginSuccess(user.id());
        sessionRepository.deleteExpired();     // 机会式清理（无定时任务）
        log.info("管理面登录成功：username={} 来源={}", username, clientInfo);
        return newSession(user.id(), clientInfo);
    }

    /** 注册：允许开放注册时任意注册；**系统尚无账号时永远允许**（首次初始化，避免锁死自己） */
    @Transactional
    public LoginView register(String usernameRaw, String password, String displayName, String clientInfo) {
        String username = normalize(usernameRaw);
        boolean firstUser = userRepository.count() == 0;
        if (!props.allowRegisterOrDefault() && !firstUser) {
            throw new BizException(BizException.REGISTER_DISABLED,
                    "注册已关闭（如需新增账号，请先登录后用现有账号创建，或把 app.api-center.auth.allow-register 置 true）");
        }
        validateUsername(username);
        validatePassword(password);
        if (userRepository.findByUsername(username).isPresent()) {
            throw new BizException(BizException.USERNAME_TAKEN, "用户名已存在：" + username);
        }
        long id = userRepository.insert(username, blankToNull(displayName), hasher.hash(password));
        log.info("管理面账号注册成功：username={}{}", username, firstUser ? "（首个账号=首次初始化）" : "");
        return newSession(id, clientInfo);
    }

    /** 退出：删除当前令牌行（即时失效，不依赖 TTL） */
    public void logout(String tokenHash) {
        if (tokenHash != null) {
            sessionRepository.deleteByToken(tokenHash);
        }
    }

    /**
     * 改密：校验原密码 → 更新摘要 → **吊销该账号其他会话**（当前会话保留，避免刚改完被踢出）。
     * 新密码不得与原密码相同。
     */
    @Transactional
    public void changePassword(long userId, String tokenHash, String oldPassword, String newPassword) {
        AdminUserRow user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(BizException.UNAUTHORIZED, "登录状态已失效，请重新登录"));
        if (!hasher.verify(oldPassword, user.passwordHash())) {
            throw new BizException(BizException.BAD_CREDENTIALS, "原密码不正确");
        }
        validatePassword(newPassword);
        if (hasher.verify(newPassword, user.passwordHash())) {
            throw new BizException(BizException.FIELD_INVALID, "新密码不能与原密码相同");
        }
        userRepository.updatePassword(userId, hasher.hash(newPassword));
        int revoked = sessionRepository.deleteByUserExcept(userId, tokenHash);
        log.info("管理面改密成功：username={} 已吊销其他会话 {} 个", user.username(), revoked);
    }

    // ---------- 令牌校验（供 AdminAuthFilter 使用） ----------

    /** 校验明文令牌 → 账号（顺带惰性续期）；无效/过期返回空 */
    public Optional<AdminUserRow> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = hasher.sha256Hex(rawToken);
        Optional<Long> userId = sessionRepository.findUserIdByToken(tokenHash);
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        AdminUserRow user = userRepository.findById(userId.get()).orElse(null);
        if (user == null || !"ENABLED".equals(user.status())) {
            return Optional.empty();
        }
        renewIfDue(tokenHash);
        return Optional.of(user);
    }

    public Optional<UserView> currentUser(long userId) {
        return userRepository.findById(userId)
                .map(u -> new UserView(u.id(), u.username(), u.displayName(), u.lastLoginAt()));
    }

    public AuthStatusView status() {
        return new AuthStatusView(props.enabledOrDefault(), userRepository.count() > 0, props.allowRegisterOrDefault());
    }

    // ---------- 内部 ----------

    private LoginView newSession(long userId, String clientInfo) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(props.sessionTtlHoursOrDefault());
        sessionRepository.insert(userId, hasher.sha256Hex(token), truncate(clientInfo, 200), expiresAt);
        AdminUserRow user = userRepository.findById(userId).orElseThrow();
        return new LoginView(token, expiresAt,
                new UserView(user.id(), user.username(), user.displayName(), user.lastLoginAt()));
    }

    /** 惰性续期：距上次续期超过 renew-interval-minutes 才写一次（最多 1 次/会话·间隔，控制写放大） */
    private void renewIfDue(String tokenHash) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newExpiry = now.plusHours(props.sessionTtlHoursOrDefault());
        // 续期判据放在 SQL 侧（避免为判断读一次再写一次）
        sessionRepository.renewIfDue(tokenHash, newExpiry, now.minusMinutes(props.renewIntervalMinutesOrDefault()));
    }

    private void validateUsername(String username) {
        if (!USERNAME.matcher(username).matches()) {
            throw BizException.fieldInvalid("用户名需 3-32 位小写字母/数字/_.- 且以字母或数字开头");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            throw BizException.fieldInvalid("密码长度需 " + PASSWORD_MIN + "-" + PASSWORD_MAX + " 位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw BizException.fieldInvalid("密码需同时包含字母与数字");
        }
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
