import { test } from 'node:test'
import assert from 'node:assert/strict'
import { adapterMatchesRole, adapterRoleHint } from './adapterUsage.mjs'

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
