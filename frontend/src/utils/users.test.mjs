import { test } from 'node:test'
import assert from 'node:assert/strict'
import { accountGuard, lockRemainText, sessionText, fmtTime, statusTagType, USER_STATUS_LABEL } from './users.mjs'
import { ADMIN, OWNER, VIEWER } from './roles.mjs'

/** 账号管理页的展示口径与操作许可（客户端镜像服务端守卫，服务端仍是权威） */

const ME = 7
const row = (over = {}) => ({
  id: 8, username: 'other', role: VIEWER, status: 'ENABLED', lockedUntil: null, sessionCount: 0, ...over
})

test('OWNER 看低等级账号：可改资料、可启停用、可重置口令、可删除', () => {
  const g = accountGuard(row(), ME, OWNER)

  assert.equal(g.self, false)
  assert.equal(g.canEditProfile, true)
  assert.equal(g.canToggleStatus, true)
  assert.equal(g.canResetPassword, true)
  assert.equal(g.canDelete, true)
  assert.equal(g.canUnlock, false)
  assert.equal(g.canChangeRole, true)
  assert.equal(g.toggleReason, '')
})

test('ADMIN 看 VIEWER 账号：可管但不能删账号、不能改角色', () => {
  const g = accountGuard(row(), ME, ADMIN)

  assert.equal(g.canToggleStatus, true)
  assert.equal(g.canResetPassword, true)
  assert.equal(g.canDelete, false)
  assert.match(g.deleteReason, /需要 OWNER/)
  assert.equal(g.canChangeRole, false)
  assert.match(g.roleReason, /需要 OWNER/)
})

test('ADMIN 看 OWNER 账号：一律不可操作（不能动比自己等级高的）', () => {
  const g = accountGuard(row({ role: OWNER }), ME, ADMIN)

  assert.equal(g.canToggleStatus, false)
  assert.equal(g.canResetPassword, false)
  assert.equal(g.canUnlock, false)
  assert.equal(g.canDelete, false)
  assert.match(g.toggleReason, /不能操作 OWNER 角色的账号/)
})

test('ADMIN 看 ADMIN 账号：同级也不可操作', () => {
  const g = accountGuard(row({ role: ADMIN }), ME, ADMIN)
  assert.equal(g.canToggleStatus, false)
})

test('VIEWER 看任何账号：全部不可（本页对 VIEWER 不可达，此处兜底）', () => {
  const g = accountGuard(row(), ME, VIEWER)

  assert.equal(g.canToggleStatus, false)
  assert.equal(g.canResetPassword, false)
  assert.equal(g.canDelete, false)
})

test('自己：禁停用/禁重置口令/禁删除，理由文案明确', () => {
  const g = accountGuard(row({ id: ME }), ME, OWNER)

  assert.equal(g.self, true)
  assert.equal(g.canEditProfile, true)      // 显示名仍可改
  assert.equal(g.canToggleStatus, false)
  assert.match(g.toggleReason, /不能停用当前登录的账号/)
  assert.equal(g.canResetPassword, false)
  assert.match(g.resetReason, /修改密码/)
  assert.equal(g.canDelete, false)
  assert.match(g.deleteReason, /不能删除当前登录的账号/)
  assert.equal(g.canChangeRole, false)                 // 不能改自己的角色
  assert.match(g.roleReason, /不能修改自己的角色/)
})

test('锁定行才给解锁操作（且等级须低于自己）', () => {
  assert.equal(accountGuard(row({ lockedUntil: '2026-09-18T10:00:00' }), ME, OWNER).canUnlock, true)
  assert.equal(accountGuard(row({ lockedUntil: null }), ME, OWNER).canUnlock, false)
  assert.equal(accountGuard(row({ role: OWNER, lockedUntil: '2026-09-18T10:00:00' }), ME, ADMIN).canUnlock, false)
  assert.equal(accountGuard(null, ME, OWNER).canUnlock, false)
})

test('锁定剩余文案：未锁定→—、已过期→—、剩余取整进位', () => {
  const now = Date.parse('2026-09-18T10:00:00')

  assert.equal(lockRemainText(null, now), '—')
  assert.equal(lockRemainText('2026-09-18T09:59:00', now), '—')          // 已过期
  assert.equal(lockRemainText('2026-09-18T10:00:30', now), '已锁定（剩 1 分钟）')
  assert.equal(lockRemainText('2026-09-18T10:05:00', now), '已锁定（剩 5 分钟）')
  assert.equal(lockRemainText('不是时间', now), '—')
})

test('会话数与时间展示的空值口径', () => {
  assert.equal(sessionText(0), '—')
  assert.equal(sessionText(3), '3 个')
  assert.equal(sessionText(undefined), '—')
  assert.equal(fmtTime('2026-09-18T10:20:30'), '2026-09-18 10:20')
  assert.equal(fmtTime(null), '—')
})

test('状态标签与文案', () => {
  assert.equal(USER_STATUS_LABEL.ENABLED, '启用')
  assert.equal(USER_STATUS_LABEL.DISABLED, '停用')
  assert.equal(statusTagType('ENABLED'), 'success')
  assert.equal(statusTagType('DISABLED'), 'info')
})
