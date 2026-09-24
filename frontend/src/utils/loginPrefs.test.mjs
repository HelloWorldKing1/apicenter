import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  loadRememberedUsername, loadRememberFlag, saveRememberedUsername,
  REMEMBER_KEY, REMEMBER_FLAG_KEY, REMEMBER_DEFAULT
} from './loginPrefs.mjs'
import { memoryStorage } from './auth.mjs'

test('默认勾选「记住用户名」且未记住时返回空串', () => {
  const s = memoryStorage()
  assert.equal(loadRememberFlag(s), REMEMBER_DEFAULT)
  assert.equal(loadRememberedUsername(s), '')
})

test('勾选并登录成功后记住用户名（去空白）', () => {
  const s = memoryStorage()
  assert.equal(saveRememberedUsername(s, '  Admin  ', true), true)
  assert.equal(loadRememberedUsername(s), 'Admin')
  assert.equal(loadRememberFlag(s), true)
})

test('取消勾选会清掉已记住的用户名', () => {
  const s = memoryStorage()
  saveRememberedUsername(s, 'admin', true)
  assert.equal(saveRememberedUsername(s, 'admin', false), false)
  assert.equal(loadRememberedUsername(s), '')
  assert.equal(loadRememberFlag(s), false)
})

test('空用户名一律不记（避免记住空值）', () => {
  const s = memoryStorage()
  assert.equal(saveRememberedUsername(s, '   ', true), false)
  assert.equal(loadRememberedUsername(s), '')
})

test('无 storage（SSR / Node 无 localStorage）时不抛异常', () => {
  assert.equal(loadRememberedUsername(null), '')
  assert.equal(loadRememberFlag(null), REMEMBER_DEFAULT)
  assert.equal(saveRememberedUsername(null, 'admin', true), false)
})

test('storage 抛异常时安全降级（隐私模式等）', () => {
  const boom = {
    getItem() { throw new Error('denied') },
    setItem() { throw new Error('denied') },
    removeItem() { throw new Error('denied') }
  }
  assert.equal(loadRememberedUsername(boom), '')
  assert.equal(loadRememberFlag(boom), REMEMBER_DEFAULT)
  assert.equal(saveRememberedUsername(boom, 'admin', true), false)
})

test('键名稳定（改动即破坏既有用户的"记住"体验）', () => {
  assert.equal(REMEMBER_KEY, 'apicenter.rememberUsername')
  assert.equal(REMEMBER_FLAG_KEY, 'apicenter.rememberUsernameOn')
})
