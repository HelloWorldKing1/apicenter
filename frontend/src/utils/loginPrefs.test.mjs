import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  loadRememberedUsername, loadRememberFlag, saveRememberedUsername,
  loadRememberPasswordFlag, saveRememberPasswordFlag, saveRememberedPassword,
  loadRememberedPassword, clearRememberedPassword, isPasswordRememberSupported,
  REMEMBER_KEY, REMEMBER_FLAG_KEY, REMEMBER_DEFAULT,
  REMEMBER_PASSWORD_FLAG_KEY, REMEMBER_PASSWORD_DEFAULT, REMEMBER_PASSWORD_RISK
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

// ---------- 记住密码（加密保管，2026-09-24） ----------

/** 内存版 IndexedDB store（注入用）：模拟"密钥+密文都留在本机" */
function memorySecretStore() {
  let rec = null
  return {
    async get() { return rec },
    async put(v) { rec = v; return true },
    async remove() { rec = null; return true },
    _raw() { return rec }        // 断言用：看落盘的是不是密文
  }
}

test('记住密码_默认关闭（涉及口令，必须显式开启）', () => {
  const s = memoryStorage()
  assert.equal(REMEMBER_PASSWORD_DEFAULT, false)
  assert.equal(loadRememberPasswordFlag(s), false)
  saveRememberPasswordFlag(s, true)
  assert.equal(loadRememberPasswordFlag(s), true)
  saveRememberPasswordFlag(s, false)
  assert.equal(loadRememberPasswordFlag(s), false)
  // 无 storage（SSR / Node 无 localStorage）⇒ 回落默认值（false），不抛
  assert.equal(loadRememberPasswordFlag(null), REMEMBER_PASSWORD_DEFAULT)
  assert.equal(REMEMBER_PASSWORD_FLAG_KEY, 'apicenter.rememberPasswordOn')
})

test('记住密码_加密往返：存的是密文，读回是明文', async () => {
  const store = memorySecretStore()
  const ok = await saveRememberedPassword('P@ssw0rd-1234', { store })
  assert.equal(ok, true)

  // **落盘内容不含明文**（核心断言）
  const raw = JSON.stringify(store._raw())
  assert.ok(!raw.includes('P@ssw0rd-1234'), '密文里不得出现明文口令')
  assert.ok(store._raw().iv && store._raw().ct, '应有随机 IV 与密文')

  const back = await loadRememberedPassword({ store })
  assert.equal(back, 'P@ssw0rd-1234')
})

test('记住密码_密钥不可导出（extractable=false，脚本拿不走密钥）', async () => {
  const store = memorySecretStore()
  await saveRememberedPassword('abc12345', { store })
  const key = store._raw().key
  assert.equal(key.extractable, false)
  await assert.rejects(() => crypto.subtle.exportKey('raw', key))
})

test('记住密码_密文被篡改 ⇒ 读回空串且自动清理（不当成"已记住"）', async () => {
  const store = memorySecretStore()
  await saveRememberedPassword('abc12345', { store })
  const rec = store._raw()
  // 篡改最后一个字节（GCM 认证标签会失败）
  const bytes = Buffer.from(rec.ct, 'base64')
  bytes[bytes.length - 1] ^= 0xff
  rec.ct = bytes.toString('base64')

  assert.equal(await loadRememberedPassword({ store }), '')
  assert.equal(store._raw(), null, '脏数据应被清掉')
})

test('记住密码_清除后读回空串', async () => {
  const store = memorySecretStore()
  await saveRememberedPassword('abc12345', { store })
  assert.equal(await clearRememberedPassword({ store }), true)
  assert.equal(await loadRememberedPassword({ store }), '')
})

test('记住密码_环境不支持（无 store / 无 subtle）⇒ 一律失败且不抛（fail-closed，绝不退回明文）', async () => {
  assert.equal(await saveRememberedPassword('abc12345', { store: null }), false)
  assert.equal(await saveRememberedPassword('abc12345', { store: memorySecretStore(), subtle: null }), false)
  assert.equal(await saveRememberedPassword('', { store: memorySecretStore() }), false)
  assert.equal(await loadRememberedPassword({ store: null }), '')
})

test('记住密码_能力探测：Node 下无 IndexedDB ⇒ 视为不支持（fail-closed）', () => {
  // Node 有 crypto.subtle，但没有 indexedDB ⇒ 必须判为「不支持」，前端会禁用该复选框
  assert.equal(typeof indexedDB, 'undefined')
  assert.equal(isPasswordRememberSupported(), false)
})

test('记住密码_风险提示文案必须点出「不能防 XSS」与「仅个人设备」', () => {
  assert.match(REMEMBER_PASSWORD_RISK, /XSS/)
  assert.match(REMEMBER_PASSWORD_RISK, /个人专用设备/)
})
