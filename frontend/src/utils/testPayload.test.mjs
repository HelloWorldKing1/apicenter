import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildTestPayload } from './testPayload.mjs'

test('入站 XML：原样发送，不做 JSON.parse（修复“XML 接口无法调试”）', () => {
  const xml = '<request><requestId>REQ-1</requestId></request>'
  assert.deepEqual(buildTestPayload('XML', xml), { data: xml, contentType: 'application/xml' })
})

test('入站 XML：即使报文长得像 JSON 也原样发送（格式由入站协议决定）', () => {
  assert.deepEqual(buildTestPayload('XML', '{"a":1}'), { data: '{"a":1}', contentType: 'application/xml' })
})

test('入站 XML：带 XML 声明的报文同样原样发送', () => {
  const xml = '<?xml version="1.0" encoding="UTF-8"?><request><event_id>evt-1</event_id></request>'
  assert.equal(buildTestPayload('XML', xml).data, xml)
})

test('入站 JSON：解析成对象（由 axios 序列化为 application/json）', () => {
  assert.deepEqual(buildTestPayload('JSON', '{"ubiNum":"1234"}'),
    { data: { ubiNum: '1234' }, contentType: 'application/json' })
})

test('入站 JSON：空报文 → 空对象（后端 body optional）', () => {
  assert.deepEqual(buildTestPayload('JSON', ''), { data: {}, contentType: 'application/json' })
  assert.deepEqual(buildTestPayload('JSON', '   '), { data: {}, contentType: 'application/json' })
})

test('入站 JSON + XML 报文：抛出**可读**提示，指明是「入站协议」不匹配', () => {
  assert.throws(
    () => buildTestPayload('JSON', '<request><requestId>REQ-1</requestId></request>'),
    (e) => e.message.includes('不是合法 JSON') && e.message.includes('入站协议'),
  )
})

test('非字符串 / 缺省协议：稳妥处理（缺省按 JSON，与后端默认一致）', () => {
  assert.equal(buildTestPayload(undefined, '{"a":1}').contentType, 'application/json')
  assert.deepEqual(buildTestPayload(null, null), { data: {}, contentType: 'application/json' })
})
