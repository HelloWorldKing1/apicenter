package com.deepx.apicenter.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色规则单测（RBAC 第一层，2026-09-18，纯单测）：
 * 把「谁能干什么」的矩阵钉死——它是端点级强制（AdminAuthFilter）与语义级校验（AdminUserService）的共用判据。
 */
class RoleRulesTest {

    @Test
    void 账号管理权限_仅OWNER与ADMIN() {
        assertThat(RoleRules.canManageAccounts(RoleRules.OWNER)).isTrue();
        assertThat(RoleRules.canManageAccounts(RoleRules.ADMIN)).isTrue();
        assertThat(RoleRules.canManageAccounts(RoleRules.VIEWER)).isFalse();
        assertThat(RoleRules.canManageAccounts(null)).isFalse();
        assertThat(RoleRules.canManageAccounts("UNKNOWN")).isFalse();
    }

    @Test
    void 删除账号与变更角色_仅OWNER() {
        assertThat(RoleRules.canDeleteAccount(RoleRules.OWNER)).isTrue();
        assertThat(RoleRules.canDeleteAccount(RoleRules.ADMIN)).isFalse();
        assertThat(RoleRules.canChangeRole(RoleRules.OWNER)).isTrue();
        assertThat(RoleRules.canChangeRole(RoleRules.ADMIN)).isFalse();
    }

    @Test
    void 只读角色判定_未知角色按最小权限处理() {
        assertThat(RoleRules.isReadOnly(RoleRules.VIEWER)).isTrue();
        assertThat(RoleRules.isReadOnly(null)).isTrue();          // 宁可少给权限
        assertThat(RoleRules.isReadOnly("UNKNOWN")).isTrue();
        assertThat(RoleRules.isReadOnly(RoleRules.ADMIN)).isFalse();
        assertThat(RoleRules.isReadOnly(RoleRules.OWNER)).isFalse();
    }

    @Test
    void 层级_OWNER大于ADMIN大于VIEWER() {
        assertThat(RoleRules.level(RoleRules.OWNER)).isGreaterThan(RoleRules.level(RoleRules.ADMIN));
        assertThat(RoleRules.level(RoleRules.ADMIN)).isGreaterThan(RoleRules.level(RoleRules.VIEWER));
        assertThat(RoleRules.level(null)).isEqualTo(RoleRules.level(RoleRules.VIEWER));
    }

    @Test
    void 可操作性_只能动比自己等级低的账号_OWNER例外() {
        assertThat(RoleRules.canOperate(RoleRules.OWNER, RoleRules.OWNER)).isTrue();   // OWNER 例外
        assertThat(RoleRules.canOperate(RoleRules.OWNER, RoleRules.ADMIN)).isTrue();
        assertThat(RoleRules.canOperate(RoleRules.ADMIN, RoleRules.VIEWER)).isTrue();
        assertThat(RoleRules.canOperate(RoleRules.ADMIN, RoleRules.ADMIN)).isFalse();  // 同级不可
        assertThat(RoleRules.canOperate(RoleRules.ADMIN, RoleRules.OWNER)).isFalse();  // 上级不可
        assertThat(RoleRules.canOperate(RoleRules.VIEWER, RoleRules.VIEWER)).isFalse();
    }

    @Test
    void 角色清单与合法性校验() {
        assertThat(RoleRules.ALL).containsExactly(RoleRules.OWNER, RoleRules.ADMIN, RoleRules.VIEWER);
        assertThat(RoleRules.isKnown("owner")).isFalse();          // 大小写敏感：归一在 service 层做
        assertThat(RoleRules.isKnown(RoleRules.OWNER)).isTrue();
        assertThat(RoleRules.isKnown("SUPER")).isFalse();
        assertThat(RoleRules.isKnown(null)).isFalse();
    }
}
