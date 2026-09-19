import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  createAuthStore, memoryStorage, usernameIssue, passwordIssue, registerIssue, redirectTarget
} from './auth.mjs'

/** 管理面登录态工具（2026-09-18）：令牌存取 / 表单预校验 / 跳转目标防开放重定向 */

test('令牌与用户存取：写入、读取、清除', () => {
  const store = createAuthStore(memoryStorage())

  assert.equal(store.getToken(), '')
  assert.equal(store.getUser(), null)

  store.setToken('tok-1')
  store.setUser({ username: 'admin', displayName: '管理员' })

  assert.equal(store.getToken(), 'tok-1')
  assert.deepEqual(store.getUser(), { username: 'admin', displayName: '管理员' })
  assert.equal(store.displayName(), '管理员')

  store.clear()
  assert.equal(store.getToken(), '')
  assert.equal(store.getUser(), null)
})

test('用户信息脏数据不抛异常（当未登录处理）', () => {
  const storage = memoryStorage()
  storage.setItem('apicenter.user', '{ not json')
  const store = createAuthStore(storage)

  assert.equal(store.getUser(), null)
  assert.equal(store.displayName(), '')
})

test('显示名缺省回落到用户名', () => {
  const store = createAuthStore(memoryStorage())
  store.setUser({ username: 'operator' })
  assert.equal(store.displayName(), 'operator')
})

test('认证开关三态：未知 / true / false', () => {
  const store = createAuthStore(memoryStorage())

  assert.equal(store.getAuthEnabled(), null)
  store.setAuthEnabled(true)
  assert.equal(store.getAuthEnabled(), true)
  store.setAuthEnabled(false)
  assert.equal(store.getAuthEnabled(), false)
})

test('令牌置空即删除（不会留下空串 token）', () => {
  const store = createAuthStore(memoryStorage())
  store.setToken('t')
  store.setToken('')
  assert.equal(store.getToken(), '')
  store.setUser({ username: 'a' })
  store.setUser(null)
  assert.equal(store.getUser(), null)
})

test('用户名规则与后端一致', () => {
  assert.equal(usernameIssue('admin'), '')
  assert.equal(usernameIssue('a_b-1.c'), '')
  assert.match(usernameIssue(''), /请输入/)
  assert.match(usernameIssue('ab'), /3-32/)
  assert.equal(usernameIssue('ABCD'), '')          // 大小写不敏感：函数内统一小写（与后端存储一致）
  assert.match(usernameIssue('1'.repeat(33)), /3-32/)
  assert.match(usernameIssue('admin space'), /3-32/)
})

test('口令规则：长度 + 字母数字混合', () => {
  assert.equal(passwordIssue('Passw0rd!'), '')
  assert.match(passwordIssue(''), /请输入/)
  assert.match(passwordIssue('Pa1!'), /8-64/)
  assert.match(passwordIssue('a'.repeat(65)), /8-64/)
  assert.match(passwordIssue('abcdefgh'), /字母与数字/)
  assert.match(passwordIssue('12345678'), /字母与数字/)
})

test('注册表单整体校验：取第一条错误，含两次密码一致性', () => {
  assert.equal(registerIssue({ username: 'admin', password: 'Passw0rd', confirm: 'Passw0rd' }), '')
  assert.match(registerIssue({ username: 'ab', password: 'Passw0rd', confirm: 'Passw0rd' }), /用户名/)
  assert.match(registerIssue({ username: 'admin', password: 'Pa1', confirm: 'Pa1' }), /8-64/)
  assert.match(registerIssue({ username: 'admin', password: 'Passw0rd', confirm: 'Passw0rd2' }), /不一致/)
})

test('跳转目标：只许站内相对路径（防开放重定向）', () => {
  assert.equal(redirectTarget('/interfaces?page=2'), '/interfaces?page=2')
  assert.equal(redirectTarget('//evil.com'), '/dashboard')
  assert.equal(redirectTarget('http://evil.com'), '/dashboard')
  assert.equal(redirectTarget('https://evil.com/x'), '/dashboard')
  assert.equal(redirectTarget(''), '/dashboard')
  assert.equal(redirectTarget(undefined), '/dashboard')
  assert.equal(redirectTarget(null), '/dashboard')
  assert.equal(redirectTarget(['/apps', '/other']), '/apps')   // query 可能是数组
  assert.equal(redirectTarget('/login'), '/dashboard')          // 回登录页无意义
})
