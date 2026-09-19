import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  OWNER, ADMIN, VIEWER, ROLE_LABEL, ROLE_OPTIONS, assignableRoles, canChangeRole, canDeleteAccount,
  canManageAccounts, canOperate, isReadOnly, level, roleLabel
} from './roles.mjs'

/** 角色矩阵（前端镜像服务端 RoleRules）：菜单/路由/按钮显示与禁用都用它 */

test('账号管理权限：OWNER/ADMIN 有，VIEWER 与未知角色没有', () => {
  assert.equal(canManageAccounts(OWNER), true)
  assert.equal(canManageAccounts(ADMIN), true)
  assert.equal(canManageAccounts(VIEWER), false)
  assert.equal(canManageAccounts(null), false)
  assert.equal(canManageAccounts('SUPER'), false)
})

test('删除账号与变更角色：仅 OWNER', () => {
  assert.equal(canDeleteAccount(OWNER), true)
  assert.equal(canDeleteAccount(ADMIN), false)
  assert.equal(canChangeRole(OWNER), true)
  assert.equal(canChangeRole(ADMIN), false)
  assert.equal(canChangeRole(VIEWER), false)
})

test('只读判定：VIEWER 与未知角色都算只读（宁可少给权限）', () => {
  assert.equal(isReadOnly(VIEWER), true)
  assert.equal(isReadOnly('SUPER'), true)
  assert.equal(isReadOnly(null), true)
  assert.equal(isReadOnly(ADMIN), false)
  assert.equal(isReadOnly(OWNER), false)
})

test('层级与可操作性：只能动低等级，OWNER 例外', () => {
  assert.ok(level(OWNER) > level(ADMIN))
  assert.ok(level(ADMIN) > level(VIEWER))
  assert.equal(canOperate(OWNER, OWNER), true)
  assert.equal(canOperate(OWNER, ADMIN), true)
  assert.equal(canOperate(ADMIN, VIEWER), true)
  assert.equal(canOperate(ADMIN, ADMIN), false)
  assert.equal(canOperate(ADMIN, OWNER), false)
})

test('可分配角色：OWNER 全部，ADMIN 只能建只读', () => {
  assert.deepEqual(assignableRoles(OWNER), [VIEWER, ADMIN, OWNER])
  assert.deepEqual(assignableRoles(ADMIN), [VIEWER])
  assert.deepEqual(assignableRoles(VIEWER), [VIEWER])
})

test('展示口径：中文标签与兜底', () => {
  assert.equal(roleLabel(OWNER), ROLE_LABEL.OWNER)
  assert.equal(roleLabel('SUPER'), 'SUPER')
  assert.equal(roleLabel(null), '—')
  assert.equal(ROLE_OPTIONS.length, 3)
  assert.ok(ROLE_OPTIONS.every((o) => ROLE_LABEL[o.value]))
})
