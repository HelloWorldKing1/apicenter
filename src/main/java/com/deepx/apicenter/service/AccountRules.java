package com.deepx.apicenter.service;

import com.deepx.apicenter.exception.BizException;

import java.util.regex.Pattern;

/**
 * 账号规则（2026-09-18）：用户名 / 口令的校验与归一化，**登录（AuthService）与账号管理（AdminUserService）共用同一套规则**。
 * 抽出的原因：两处各写一遍必然漂移（前端 `utils/auth.mjs` 是第三份镜像，只做即时提示，后端仍是权威）。
 */
public final class AccountRules {

    /** 用户名：3-32 位，字母/数字/下划线/点/连字符，且以字母或数字开头 */
    public static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9_.-]{2,31}$");
    public static final int PASSWORD_MIN = 8;
    public static final int PASSWORD_MAX = 64;

    private AccountRules() {
    }

    /** 归一化：去空白 + 统一小写（避免 Admin / admin 视为两个账号） */
    public static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    public static void validateUsername(String username) {
        if (!USERNAME.matcher(username).matches()) {
            throw BizException.fieldInvalid("用户名需 3-32 位小写字母/数字/_.- 且以字母或数字开头");
        }
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            throw BizException.fieldInvalid("密码长度需 " + PASSWORD_MIN + "-" + PASSWORD_MAX + " 位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw BizException.fieldInvalid("密码需同时包含字母与数字");
        }
    }

    /** 显示名等可选文本：空白 → null（避免存空串导致「看起来有值」） */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
