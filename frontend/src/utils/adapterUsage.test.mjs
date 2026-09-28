import { test } from 'node:test'
import assert from 'node:assert/strict'
import { adapterMatchesRole, adapterRoleHint, implOptionLabel } from './adapterUsage.mjs'

test('HMAC 回调验签：只允许回调验签侧（真实踩坑的那一个）', () => {
  assert.equal(adapterMatchesRole('HmacCallbackVerifyAdapter', 'CALLBACK'), true)
  assert.equal(adapterMatchesRole('HmacCallbackVerifyAdapter', 'OUTBOUND'), false)
  assert.equal(adapterRoleHint('HmacCallbackVerifyAdapter'), '仅回调验签')
})

test('Bearer / API Key：只允许出站签名侧', () => {
  for (const impl of ['BearerTokenAuthAdapter', 'ApiKeyAuthAdapter']) {
    assert.equal(adapterMatchesRole(impl, 'OUTBOUND'), true)
    assert.equal(adapterMatchesRole(impl, 'CALLBACK'), false)
    assert.equal(adapterRoleHint(impl), '仅出站签名')
  }
})

test('Noop / 未知 / 自定义 impl：两侧都允许（不误挡）', () => {
  for (const impl of ['NoopAuthAdapter', 'MyCustomAuthAdapter']) {
    assert.equal(adapterMatchesRole(impl, 'OUTBOUND'), true)
    assert.equal(adapterMatchesRole(impl, 'CALLBACK'), true)
    assert.equal(adapterRoleHint(impl), '两者皆可')
  }
})

test('空 impl：两侧都不匹配（无可选项）', () => {
  assert.equal(adapterMatchesRole('', 'OUTBOUND'), false)
  assert.equal(adapterMatchesRole(null, 'CALLBACK'), false)
})

// ---------- v1.2：入站鉴权方式（CLIENT_AUTH） ----------

test('入站鉴权角色只认 4 个已知实现（未知 impl 一律不允许）', () => {
  for (const impl of ['ClientApiKeyVerifyAdapter', 'ClientHmacVerifyAdapter',
    'ClientBearerVerifyAdapter', 'ClientIpWhitelistVerifyAdapter']) {
    assert.equal(adapterMatchesRole(impl, 'CLIENT_AUTH'), true, impl)
  }
  assert.equal(adapterMatchesRole('SomeCustomAdapter', 'CLIENT_AUTH'), false)
  assert.equal(adapterMatchesRole('HmacCallbackVerifyAdapter', 'CLIENT_AUTH'), false)
  assert.equal(adapterMatchesRole('BearerTokenAuthAdapter', 'CLIENT_AUTH'), false)
})

test('入站鉴权的 4 个实现仍可绑回调验签，但不能当出站签名', () => {
  assert.equal(adapterMatchesRole('ClientApiKeyVerifyAdapter', 'CALLBACK'), true)
  assert.equal(adapterMatchesRole('ClientApiKeyVerifyAdapter', 'OUTBOUND'), false)
})

test('角色提示文案覆盖入站鉴权侧', () => {
  assert.equal(adapterRoleHint('ClientApiKeyVerifyAdapter'), '入站鉴权 / 回调验签')
})

test('云厂商签名（AK/SK 直签）只应出现在出站签名侧，不能当回调验签', () => {
  assert.equal(adapterMatchesRole('CloudSignatureAdapter', 'OUTBOUND'), true)
  assert.equal(adapterMatchesRole('CloudSignatureAdapter', 'CALLBACK'), false)
  assert.equal(adapterMatchesRole('CloudSignatureAdapter', 'CLIENT_AUTH'), false)
  assert.equal(adapterRoleHint('CloudSignatureAdapter'), '仅出站签名')
})

// ---------- implOptionLabel（2026-09-25）：把「用途」写进实现下拉标签，专治 HMAC 三兄弟选错 ----------

test('implOptionLabel_标签含用途与 impl', () => {
  // 同样是 HMAC：出站签名 / 入站回调 / 入站鉴权三个名字极像，标签必须能一眼区分
  assert.equal(implOptionLabel({ name: 'HMAC 签名', impl: 'HmacAuthAdapter' }),
    'HMAC 签名 · 仅出站签名（HmacAuthAdapter）')
  assert.equal(implOptionLabel({ name: 'HMAC 回调验签', impl: 'HmacCallbackVerifyAdapter' }),
    'HMAC 回调验签 · 仅回调验签（HmacCallbackVerifyAdapter）')
  assert.equal(implOptionLabel({ name: '调用方 HMAC 验签', impl: 'ClientHmacVerifyAdapter' }),
    '调用方 HMAC 验签 · 入站鉴权 / 回调验签（ClientHmacVerifyAdapter）')
})

test('implOptionLabel_缺名称时退化为 impl_空值返回空串', () => {
  assert.equal(implOptionLabel({ impl: 'ApiKeyAuthAdapter' }), 'ApiKeyAuthAdapter · 仅出站签名（ApiKeyAuthAdapter）')
  assert.equal(implOptionLabel(null), '')
  assert.equal(implOptionLabel({}), '')
})
