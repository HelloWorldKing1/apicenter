import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  beautifyBody, bodyFormatOf, bodyFormatLabel, bodyHintFor, bodyPlaceholderFor, bodySkeleton,
} from './requestBody.mjs'

test('格式随「入站协议」：XML → XML；其余（含缺省）→ JSON', () => {
  assert.equal(bodyFormatOf('XML'), 'XML')
  assert.equal(bodyFormatOf('JSON'), 'JSON')
  assert.equal(bodyFormatOf(undefined), 'JSON')
  assert.equal(bodyFormatOf(null), 'JSON')
  assert.ok(bodyFormatLabel('XML').startsWith('XML'))
  assert.ok(bodyFormatLabel('JSON').includes('入站协议'))
})

test('初始骨架随协议：XML 给元素骨架，JSON 给 {}（修"XML 接口预填 {}"）', () => {
  assert.equal(bodySkeleton('XML'), '<request></request>')
  assert.equal(bodySkeleton('JSON'), '{}')
})

test('XML 报文：按 XML 缩进（无损，标签与文本不变）', () => {
  const r = beautifyBody('<request><event_id>evt-1</event_id></request>', 'XML')
  assert.equal(r.ok, true)
  assert.ok(r.text.includes('\n'), r.text)
  assert.ok(r.text.includes('<event_id>evt-1</event_id>'), r.text)
  assert.ok(r.message.includes('只增删空白'), r.message)
})

test('JSON 报文：按 JSON 缩进，且**不改写 token**（19 位数字/1.10 保真）', () => {
  const src = '{"id":1234567890123456789,"price":1.10}'
  const r = beautifyBody(src, 'JSON')
  assert.equal(r.ok, true)
  assert.ok(r.text.includes('1234567890123456789'), r.text)   // 不被科学计数/尾数改写
  assert.ok(r.text.includes('1.10'), r.text)                  // 不被规范成 1.1
  assert.ok(r.text.includes('\n'), r.text)
})

test('格式不符：**不吞掉输入**，返回原文 + 可读原因', () => {
  const xmlToJson = beautifyBody('<request><a>1</a></request>', 'JSON')
  assert.equal(xmlToJson.ok, false)
  assert.equal(xmlToJson.text, '<request><a>1</a></request>')  // 原文保留
  assert.ok(xmlToJson.message.includes('不是合法 JSON'), xmlToJson.message)

  const jsonToXml = beautifyBody('{"a":1}', 'XML')
  assert.equal(jsonToXml.ok, false)
  assert.equal(jsonToXml.text, '{"a":1}')
  assert.ok(jsonToXml.message.includes('不像是 XML'), jsonToXml.message)
})

test('空内容：视为 OK 且不改动（不报错）', () => {
  for (const empty of ['', '   ', null, undefined]) {
    const r = beautifyBody(empty, 'XML')
    assert.equal(r.ok, true)
    assert.equal(r.message, '')
  }
})

test('提示语把**两个方向的报错**都写出来（省一次往返）', () => {
  const xmlHint = bodyHintFor('XML')
  assert.ok(xmlHint.includes('必须填 XML'), xmlHint)
  assert.ok(xmlHint.includes('40002'), xmlHint)
  const jsonHint = bodyHintFor('JSON', { callback: true })
  assert.ok(jsonHint.includes('回调报文'), jsonHint)
  assert.ok(jsonHint.includes('必须填合法 JSON'), jsonHint)
})

test('⚠️ 提示语必须**短**（过长会挤乱工具栏布局 —— 2026-09-22 实测回归防线）', () => {
  for (const p of ['XML', 'JSON']) {
    assert.ok(bodyHintFor(p).length <= 60, `${p}: ${bodyHintFor(p).length}`)
    assert.ok(bodyHintFor(p, { callback: true }).length <= 70, `${p}/callback`)
  }
})

test('示例放 placeholder（不占提示行），且随协议与场景变化', () => {
  assert.equal(bodyPlaceholderFor('XML'), '<request><requestId>REQ-1</requestId></request>')
  assert.equal(bodyPlaceholderFor('XML', { callback: true }), '<request><event_id>evt-1</event_id></request>')
  assert.equal(bodyPlaceholderFor('JSON'), '{"requestId":"REQ-1"}')
  assert.equal(bodyPlaceholderFor('JSON', { callback: true }), '{"event_id":"evt-1"}')
})
