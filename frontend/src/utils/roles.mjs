/**
 * 角色（RBAC 第一层，2026-09-18）前端镜像：与服务端 `RoleRules` 一一对应。
 *
 * 服务端强制在**两处**（端点级 `AdminAuthFilter` + 语义级 `AdminUserService`），本模块只负责
 * 「菜单/路由/按钮该不该显示」与「点不了时给什么理由」——**服务端永远是权威**。
 */

export const OWNER = 'OWNER'
export const ADMIN = 'ADMIN'
export const VIEWER = 'VIEWER'

export const ROLE_LABEL = { OWNER: '拥有者', ADMIN: '管理员', VIEWER: '只读' }
export const ROLE_TAG = { OWNER: 'danger', ADMIN: 'warning', VIEWER: 'info' }
/** 可分配角色 + 中文说明（新建/编辑弹窗的下拉用） */
export const ROLE_OPTIONS = [
  { value: 'VIEWER', label: '只读（VIEWER）——可查看管理面，不能修改任何配置' },
  { value: 'ADMIN', label: '管理员（ADMIN）——可改配置、可管理低等级账号（不能删账号/改角色）' },
  { value: 'OWNER', label: '拥有者（OWNER）——全部权限，含删除账号与分配角色' }
]

/** 等级：与服务端一致（未知/null 按最低处理） */
export function level(role) {
  return role === OWNER ? 3 : role === ADMIN ? 2 : 1
}

export function roleLabel(role) {
  return ROLE_LABEL[role] || role || '—'
}

/** 是否只读（写操作会被服务端 40302 拦掉，前端提前禁用入口） */
export function isReadOnly(role) {
  return level(role) < 2
}

/** 能否进入账号管理（服务端 40303；前端据此隐藏菜单与路由） */
export function canManageAccounts(role) {
  return level(role) >= 2
}

export function canDeleteAccount(role) {
  return role === OWNER
}

export function canChangeRole(role) {
  return role === OWNER
}

/** 能否操作目标账号（不能动比自己等级高的；也不能动自己 —— 后者由调用方额外判断） */
export function canOperate(operatorRole, targetRole) {
  return operatorRole === OWNER || level(operatorRole) > level(targetRole)
}

/** 新建账号时可分配的角色（ADMIN 只能建只读账号） */
export function assignableRoles(role) {
  return canChangeRole(role) ? [VIEWER, ADMIN, OWNER] : [VIEWER]
}
