import { test } from 'node:test'
import assert from 'node:assert/strict'
import { credentialHint } from './credentialHint.mjs'

test('新建态（未保存）：不给任何提示', () => {
  assert.equal(credentialHint({ isEdit: false, credentialNeeded: true, hasCredential: false, kind: 'OUTBOUND' }), '')
  assert.equal(credentialHint({ isEdit: false, credentialNeeded: false, hasCredential: true, kind: 'OUTBOUND' }), '')
})

test('**已解绑但凭证仍在**：说明原因并给出「吊销→删除」路径（本次修复的报告场景）', () => {
  const hint = credentialHint({ isEdit: true, credentialNeeded: false, hasCredential: true, status: 'ACTIVE', kind: 'OUTBOUND' })
  assert.ok(hint.includes('未绑定适配器'), hint)
  assert.ok(hint.includes('已不再被使用'), hint)
  assert.ok(hint.includes('吊销'), hint)
  assert.ok(hint.includes('删除'), hint)
})

test('已解绑且本来就没凭证：不提示', () => {
  assert.equal(credentialHint({ isEdit: true, credentialNeeded: false, hasCredential: false, kind: 'OUTBOUND' }), '')
})

test('已绑定 + ACTIVE 凭证：不提示', () => {
  assert.equal(credentialHint({ isEdit: true, credentialNeeded: true, hasCredential: true, status: 'ACTIVE', kind: 'CALLBACK' }), '')
})

test('已绑定但缺凭证：OUTBOUND 讲 401、CALLBACK 讲 40100', () => {
  const out = credentialHint({ isEdit: true, credentialNeeded: true, hasCredential: false, kind: 'OUTBOUND' })
  assert.ok(out.includes('401'), out)
  const cb = credentialHint({ isEdit: true, credentialNeeded: true, hasCredential: false, kind: 'CALLBACK' })
  assert.ok(cb.includes('40100'), cb)
  // CALLBACK 且有已发布入站接口 → 措辞更重
  const cb2 = credentialHint({
    isEdit: true, credentialNeeded: true, hasCredential: false, kind: 'CALLBACK', hasPublishedInbound: true,
  })
  assert.ok(cb2.includes('已发布入站接口'), cb2)
})
