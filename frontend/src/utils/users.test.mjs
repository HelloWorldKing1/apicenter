import { test } from 'node:test'
import assert from 'node:assert/strict'
import { accountGuard, lockRemainText, sessionText, fmtTime, statusTagType, USER_STATUS_LABEL } from './users.mjs'

/** 账号管理页的展示口径与操作许可（客户端镜像服务端守卫，服务端仍是权威） */

const ME = 7
const row = (over = {}) => ({ id: 8, username: 'other', status: 'ENABLED', lockedUntil: null, sessionCount: 0, ...over })

test('他人账号：可改资料、可启停用、可重置口令、可删除', () => {
  const g = accountGuard(row(), ME)

  assert.equal(g.self, false)
  assert.equal(g.canEditProfile, true)
  assert.equal(g.canToggleStatus, true)
  assert.equal(g.canResetPassword, true)
  assert.equal(g.canDelete, true)
  assert.equal(g.canUnlock, false)
  assert.equal(g.toggleReason, '')
})

test('自己：禁停用/禁重置口令/禁删除，理由文案明确', () => {
  const g = accountGuard(row({ id: ME }), ME)

  assert.equal(g.self, true)
  assert.equal(g.canEditProfile, true)      // 显示名仍可改
  assert.equal(g.canToggleStatus, false)
  assert.match(g.toggleReason, /不能停用当前登录的账号/)
  assert.equal(g.canResetPassword, false)
  assert.match(g.resetReason, /修改密码/)
  assert.equal(g.canDelete, false)
  assert.match(g.deleteReason, /不能删除当前登录的账号/)
})

test('锁定行才给解锁操作', () => {
  assert.equal(accountGuard(row({ lockedUntil: '2026-09-18T10:00:00' }), ME).canUnlock, true)
  assert.equal(accountGuard(row({ lockedUntil: null }), ME).canUnlock, false)
  assert.equal(accountGuard(null, ME).canUnlock, false)
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
