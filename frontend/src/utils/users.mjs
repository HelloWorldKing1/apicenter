/**
 * 账号管理页的展示口径与**操作许可**（2026-09-18）。
 *
 * 说明：v1 无角色/权限，服务端只有「不把系统锁死」的两条底线（最后一个可用账号 / 不能动自己，
 * 见 `AdminUserService.guardLockout`）。本模块把这两条底线的**客户端镜像**抽成纯函数：
 * 用于按钮禁用与提示，让用户点之前就知道能不能点；**服务端仍是权威**（前端只是省一次往返）。
 */

import { canDeleteAccount, canOperate, canChangeRole } from './roles.mjs'

export const USER_STATUS_LABEL = { ENABLED: '启用', DISABLED: '停用' }

/** 状态标签色（Element Plus tag type） */
export function statusTagType(status) {
  return status === 'ENABLED' ? 'success' : 'info'
}

/**
 * 某行账号对当前登录账号而言，可执行哪些操作（**客户端镜像**服务端两条守卫 + 角色层级）。
 * @param {object} row 列表行（含 id / role / status / lockedUntil）
 * @param {number} meId 当前登录账号 id
 * @param {string} meRole 当前登录账号角色（OWNER / ADMIN / VIEWER）
 */
export function accountGuard(row, meId, meRole) {
  const self = !!row && row.id === meId
  const lowerRank = !!row && canOperate(meRole, row.role)   // 不能动等级 ≥ 自己的账号
  const rankReason = lowerRank ? '' : `不能操作 ${row?.role || '该'} 角色的账号（需要更高权限）`
  return {
    self,
    /** 显示名随时可改（含自己）；状态不能改自己（停用自己 = 把自己踢出去） */
    canEditProfile: true,
    canToggleStatus: !self && lowerRank,
    toggleReason: self ? '不能停用当前登录的账号' : rankReason,
    canResetPassword: !self && lowerRank,
    resetReason: self ? '重置自己的口令请用右上角「账号名 → 修改密码」' : rankReason,
    /** 解锁仅在确实处于锁定期、且等级低于自己时有意义 */
    canUnlock: !!row && !!row.lockedUntil && lowerRank,
    canDelete: !self && lowerRank && canDeleteAccount(meRole),
    deleteReason: self ? '不能删除当前登录的账号'
      : !canDeleteAccount(meRole) ? '删除账号需要 OWNER 角色' : rankReason,
    /** 角色下拉是否可用（仅 OWNER，且不能改自己） */
    canChangeRole: canChangeRole(meRole) && !self,
    roleReason: !canChangeRole(meRole) ? '变更角色需要 OWNER 角色' : self ? '不能修改自己的角色' : ''
  }
}

/**
 * 锁定期剩余文案：`已锁定（剩 3 分钟）` / `已锁定` / `—`。
 * 用本地时间比较**服务端返回的绝对时间**（时间格式与后端 LocalDateTime 一致，按本地时区解析）。
 */
export function lockRemainText(lockedUntil, now = Date.now()) {
  if (!lockedUntil) {
    return '—'
  }
  const until = new Date(String(lockedUntil).replace(' ', 'T')).getTime()
  if (Number.isNaN(until) || until <= now) {
    return '—'
  }
  const minutes = Math.max(1, Math.ceil((until - now) / 60000))
  return `已锁定（剩 ${minutes} 分钟）`
}

/** 会话数展示：0 → `—`（避免「0 个」看起来像异常） */
export function sessionText(count) {
  const n = Number(count) || 0
  return n > 0 ? `${n} 个` : '—'
}

/** 时间展示：YYYY-MM-DD HH:mm（空值 → `—`） */
export function fmtTime(value) {
  if (!value) {
    return '—'
  }
  return String(value).replace('T', ' ').slice(0, 16)
}

/**
 * 权限（v1 无角色）下的**操作审计提示**：账号管理页顶部常驻说明，
 * 明确「任何已登录账号都能管理账号」这一现状，避免误以为有权限分级。
 */
export const NO_RBAC_NOTICE = '角色模型（OWNER / ADMIN / VIEWER）：ADMIN 可管理低等级账号但不能删除账号或变更角色；只读角色无法进入本页。两条安全底线由服务端强制：不能动最后一个可用账号、不能动自己（含最后一个 OWNER 不可降级/删除）。'
