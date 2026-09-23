import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  diffSetting, relaxationConfirmText, ownerTypeLabel, ownerIdRequired,
  credentialStatusLabel, kindLabel, EMPTY_SETTING
} from './inboundAuth.mjs'

test('取消强制自报主体_判为放松类变更', () => {
  const d = diffSetting({ ...EMPTY_SETTING, requireClientId: true }, { ...EMPTY_SETTING, requireClientId: false })
  assert.equal(d.relaxation, true)
  assert.match(d.reasons.join(), /强制自报主体/)
})

test('开启强制自报主体_不算放松（是收紧）', () => {
  const d = diffSetting({ ...EMPTY_SETTING, requireClientId: false }, { ...EMPTY_SETTING, requireClientId: true })
  assert.equal(d.relaxation, false)
})

test('换平台默认方式_一律按放松处理（宽严无法自动判定）', () => {
  const d = diffSetting({ ...EMPTY_SETTING, defaultAdapterId: 'ADP-1' }, { ...EMPTY_SETTING, defaultAdapterId: 'ADP-2' })
  assert.equal(d.relaxation, true)
  assert.match(d.reasons.join(), /平台默认鉴权方式/)
})

test('未改动_不算放松', () => {
  const d = diffSetting({ defaultAdapterId: 'ADP-1', requireClientId: true }, { defaultAdapterId: 'ADP-1', requireClientId: true })
  assert.equal(d.relaxation, false)
  assert.deepEqual(d.reasons, [])
})

test('确认文案带影响面与“立即生效”提示', () => {
  const text = relaxationConfirmText(['r1'], 3)
  assert.match(text, /r1/)
  assert.match(text, /受影响接口：3/)
  assert.match(text, /立即生效/)
})

test('属主类型与 ownerId 必填口径', () => {
  assert.equal(ownerTypeLabel('PLATFORM'), '平台共享池')
  assert.equal(ownerIdRequired('PLATFORM'), false)
  assert.equal(ownerIdRequired('INTERFACE'), true)
  assert.equal(ownerIdRequired('CLIENT'), true)
})

test('凭证状态与类型的中文口径', () => {
  assert.match(credentialStatusLabel('ROTATING'), /轮换并存/)
  assert.equal(kindLabel('API_KEY'), 'API Key')
  assert.equal(kindLabel(''), '—')
})
