/**
 * 账号管理页的展示口径与**操作许可**（2026-09-18）。
 *
 * 说明：v1 无角色/权限，服务端只有「不把系统锁死」的两条底线（最后一个可用账号 / 不能动自己，
 * 见 `AdminUserService.guardLockout`）。本模块把这两条底线的**客户端镜像**抽成纯函数：
 * 用于按钮禁用与提示，让用户点之前就知道能不能点；**服务端仍是权威**（前端只是省一次往返）。
 */

export const USER_STATUS_LABEL = { ENABLED: '启用', DISABLED: '停用' }

/** 状态标签色（Element Plus tag type） */
export function statusTagType(status) {
  return status === 'ENABLED' ? 'success' : 'info'
}

/**
 * 某行账号对当前登录账号而言，可执行哪些操作。
 * @param {object} row 列表行（含 id / status / lockedUntil）
 * @param {number} meId 当前登录账号 id
 */
export function accountGuard(row, meId) {
  const self = !!row && row.id === meId
  return {
    self,
    /** 显示名随时可改（含自己）；状态不能改自己（停用自己 = 把自己踢出去） */
    canEditProfile: true,
    canToggleStatus: !self,
    toggleReason: self ? '不能停用当前登录的账号' : '',
    canResetPassword: !self,
    resetReason: self ? '重置自己的口令请用右上角「账号名 → 修改密码」' : '',
    /** 解锁仅在确实处于锁定期时有意义 */
    canUnlock: !!row && !!row.lockedUntil,
    canDelete: !self,
    deleteReason: self ? '不能删除当前登录的账号' : ''
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
export const NO_RBAC_NOTICE = '当前版本无权限分级：任何已登录账号都可以管理账号。请勿停用/删除最后一个可用账号（服务端已拦截）。'
