import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildCredentialPayload } from './credentialDraft.mjs'

/** 云厂商签名适配器的字段（真实元数据：前两个必填、token 可选） */
const CLOUD_FIELDS = [
  { key: 'secretId', label: 'SecretId', required: true },
  { key: 'secretKey', label: 'SecretKey', required: true },
  { key: 'token', label: '临时凭证 Token', required: false }
]

test('必填填齐、可选留空 ⇒ 成功，且可选字段整键省略（不写空串/null）', () => {
  const r = buildCredentialPayload(CLOUD_FIELDS, { secretId: 'AKID-TEST', secretKey: 'SK-TEST' })
  assert.equal(r.ok, true)
  assert.deepEqual(JSON.parse(r.payload), { secretId: 'AKID-TEST', secretKey: 'SK-TEST' })
  assert.ok(!r.payload.includes('token'), '可选字段不应出现在 JSON 里')
})

test('必填填齐 + 可选也填 ⇒ 三个字段都进 JSON（STS 场景）', () => {
  const r = buildCredentialPayload(CLOUD_FIELDS, { secretId: 'a', secretKey: 'b', token: 'STS-1' })
  assert.deepEqual(JSON.parse(r.payload), { secretId: 'a', secretKey: 'b', token: 'STS-1' })
})

test('缺必填 ⇒ 失败，且只点名必填字段（并说明其余可选）', () => {
  const r = buildCredentialPayload(CLOUD_FIELDS, { secretKey: 'SK-TEST' })
  assert.equal(r.ok, false)
  assert.match(r.message, /请填写：SecretId/)
  assert.match(r.message, /其余为可选：临时凭证 Token/)
})

test('全部留空 ⇒ 不提交凭证（payload=null，凭证可事后补）', () => {
  const r = buildCredentialPayload(CLOUD_FIELDS, {})
  assert.deepEqual(r, { ok: true, payload: null })
  assert.deepEqual(buildCredentialPayload(CLOUD_FIELDS, { secretId: '  ', secretKey: ' ' }), { ok: true, payload: null })
})

test('单字段适配器（如通用 HMAC）⇒ payload 是裸字符串（保持既有口径）', () => {
  const r = buildCredentialPayload([{ key: '__value', label: '密钥 / Token', required: true }], { __value: 'secret' })
  assert.equal(r.payload, 'secret')
})

test('未声明 required ⇒ 视为必填（保守，与旧行为一致）', () => {
  const r = buildCredentialPayload([{ key: 'a', label: 'A' }, { key: 'b', label: 'B', required: false }], { b: 'only-b' })
  assert.equal(r.ok, false)
  assert.match(r.message, /请填写：A/)
})

test('值两端空白被裁剪', () => {
  const r = buildCredentialPayload([{ key: 'k', label: 'K' }], { k: '  v  ' })
  assert.equal(r.payload, 'v')
})
