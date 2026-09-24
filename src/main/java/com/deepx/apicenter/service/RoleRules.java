package com.deepx.apicenter.service;

import java.util.List;

/**
 * 角色规则（RBAC 第一层，2026-09-18）：**角色只有三个，权限差异集中在两处**——
 * ① 管理面「写操作」是否放行；② 「账号管理」是否可用。
 *
 * <table>
 *   <tr><th>角色</th><th>管理面读写</th><th>账号管理</th><th>删除账号 / 变更角色</th></tr>
 *   <tr><td>OWNER</td><td>全部</td><td>全部</td><td>可以</td></tr>
 *   <tr><td>ADMIN</td><td>全部</td><td>新建 / 启停用 / 重置口令 / 解锁 / 改显示名</td><td>不可</td></tr>
 *   <tr><td>VIEWER</td><td><b>只读</b>（GET；个人改密与退出除外）</td><td>不可（入口不显示）</td><td>不可</td></tr>
 * </table>
 *
 * <p>强制位置（只有这两处，避免散落各 Controller）：
 * <ul>
 *   <li>{@code AdminAuthFilter}：VIEWER 的非 GET 请求（`/api/admin/**`，`/api/admin/auth/**` 除外）→ 40302；
 *       `/api/admin/users/**` 要求 OWNER/ADMIN → 40303；
 *       `/api/admin/inbound-auth/**` 与 `/api/admin/inbound-credentials/**`（入站鉴权管理，**读写都算**）
 *       要求 OWNER/ADMIN → 40305；其中 `PUT /api/admin/inbound-auth/settings`（**平台设置**）再收紧到 OWNER → 40304；</li>
 *   <li>{@link AdminUserService}：拒绝「动比自己权限高的账号」「改自己角色」「降级最后一个 OWNER」「ADMIN 删账号」
 *       这类**语义级**越权（403/400 由具体校验返回）。</li>
 * </ul>
 *
 * <p>层级规则（{@link #level}）：OWNER &gt; ADMIN &gt; VIEWER —— 高等级可以管理**低等级**账号，反之不可。
 */
public final class RoleRules {

    public static final String OWNER = "OWNER";
    public static final String ADMIN = "ADMIN";
    public static final String VIEWER = "VIEWER";

    /** 可分配角色（前端下拉与后端校验共用同一份清单） */
    public static final List<String> ALL = List.of(OWNER, ADMIN, VIEWER);

    private RoleRules() {
    }

    public static boolean isKnown(String role) {
        return role != null && ALL.contains(role);
    }

    /** 层级：未知角色按最低（VIEWER）处理 —— 宁可少给权限，不给多 */
    public static int level(String role) {
        return switch (role == null ? "" : role) {
            case OWNER -> 3;
            case ADMIN -> 2;
            default -> 1;
        };
    }

    /** 能否进入账号管理（列表 / 新建 / 编辑 / 重置 / 解锁 / 删除的门槛） */
    /**
     * 能否**访问入站鉴权管理**（读+写：平台设置 / 凭证池）—— 要求 **ADMIN / OWNER**。
     *
     * <p>2026-09-24 决策（需求方选 B+C）：**读也要求 ADMIN/OWNER** ⇒ VIEWER 连页面与接口都不可达
     * （理由：平台设置与凭证池台账属于**安全配置面**，只读账号没必要看到"平台默认方式是什么、
     * 有哪些密钥、指纹是多少"）；而**修改平台设置**进一步收紧到 OWNER（见下）。
     */
    public static boolean canManageInboundAuth(String role) {
        return level(role) >= 2;
    }

    /**
     * 能否修改「入站鉴权」的**平台设置**（平台默认鉴权方式 / 是否强制自报主体）—— **仅 OWNER**。
     *
     * <p>为什么提级到 OWNER：这两项是**安全策略**，一改就是**对所有未单独绑定鉴权方式的接口放宽/收紧**；
     * 而日常接入动作（发放/吊销凭证、接口级绑定方式）仍是 ADMIN/OWNER 即可 ——
     * 即"日常不打断，放宽策略只有拥有者能动"（2026-09-24 决策，见《入站鉴权设计方案.md》v1.2 §7.3）。
     */
    public static boolean canChangeInboundAuthSetting(String role) {
        return OWNER.equals(role);
    }

    public static boolean canManageAccounts(String role) {
        return level(role) >= 2;
    }

    /** 能否删除账号、变更他人角色：仅 OWNER */
    public static boolean canDeleteAccount(String role) {
        return OWNER.equals(role);
    }

    public static boolean canChangeRole(String role) {
        return OWNER.equals(role);
    }

    /** 管理面只读角色：写操作一律 403（个人账号操作除外，见 AdminAuthFilter 的豁免） */
    public static boolean isReadOnly(String role) {
        return level(role) < 2;
    }

    /** 能否管理目标账号（不能动比自己等级高的，也不能动自己 —— 后者由 AdminUserService 判定） */
    public static boolean canOperate(String operatorRole, String targetRole) {
        return level(operatorRole) > level(targetRole) || OWNER.equals(operatorRole);
    }
}
