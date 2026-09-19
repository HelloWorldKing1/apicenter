package com.deepx.apicenter.service;

import com.deepx.apicenter.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号规则单测（2026-09-18，纯单测无 Spring / 无 DB）：
 * 登录（AuthService）与账号管理（AdminUserService）共用同一套规则，本类把规则本身钉死，避免两处漂移。
 */
class AccountRulesTest {

    @Test
    void 用户名归一化_去空白且小写() {
        assertThat(AccountRules.normalize("  Admin.User  ")).isEqualTo("admin.user");
        assertThat(AccountRules.normalize(null)).isEmpty();
        assertThat(AccountRules.normalize("")).isEmpty();
    }

    @Test
    void 合法用户名() {
        assertThat(AccountRules.USERNAME.matcher("admin").matches()).isTrue();
        assertThat(AccountRules.USERNAME.matcher("it_auth.01-x").matches()).isTrue();
        assertThat(AccountRules.USERNAME.matcher("a".repeat(32)).matches()).isTrue();
    }

    @Test
    void 非法用户名一律40001() {
        for (String bad : new String[]{"ab", "a".repeat(33), "_lead", ".dot", "has space", "中文名", "admin@"}) {
            assertThatThrownBy(() -> AccountRules.validateUsername(bad))
                    .as("应拒绝用户名：%s", bad)
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("用户名");
        }
    }

    @Test
    void 口令长度与字母数字混合() {
        AccountRules.validatePassword("Passw0rd!");   // 不抛即合法
        assertThatThrownBy(() -> AccountRules.validatePassword(null)).hasMessageContaining("8-64");
        assertThatThrownBy(() -> AccountRules.validatePassword("Pa1!")).hasMessageContaining("8-64");
        assertThatThrownBy(() -> AccountRules.validatePassword("a".repeat(65))).hasMessageContaining("8-64");
        assertThatThrownBy(() -> AccountRules.validatePassword("abcdefgh")).hasMessageContaining("字母与数字");
        assertThatThrownBy(() -> AccountRules.validatePassword("12345678")).hasMessageContaining("字母与数字");
    }

    @Test
    void 二义性校验错误码统一为40001() {
        assertThatThrownBy(() -> AccountRules.validateUsername("x"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BizException.FIELD_INVALID));
    }

    @Test
    void 显示名空白归一为null() {
        assertThat(AccountRules.blankToNull(" 张三 ")).isEqualTo("张三");
        assertThat(AccountRules.blankToNull("   ")).isNull();
        assertThat(AccountRules.blankToNull(null)).isNull();
    }
}
