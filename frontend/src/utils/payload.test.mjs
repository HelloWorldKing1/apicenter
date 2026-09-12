/**
 * 报文格式化单测（零依赖：Node 24 内置 test runner）。
 * 运行：`npm test`（= node --test src/utils）或 `node --test src/utils/payload.test.mjs`。
 * 文件用 .mjs：与 payload.mjs 同源，Node 直跑无需给 package.json 加 "type": "module"。
 *
 * 核心契约（评审拍板）：**美化只增删空白，不改写任何 token** —— 下面用 19 位数字等断言把它锁死。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { analyzePayload, isJsonLike, isXmlLike, MAX_FORMAT_CHARS } from './payload.mjs'

// ---------- JSON ----------

test('紧凑 JSON：缩进 + token 逐字节不变（19 位数字 / 1.10 / 1e3 / 重复键）', () => {
  const raw = '{"seller_id":7494312521977267257,"amount":1.10,"exp":1e3,"a":1,"a":2,"note":"订单 7494312521977267257 已受理"}'
  const r = analyzePayload(raw)
  assert.equal(r.lang, 'json')
  assert.equal(r.formatted, true)

  // 关键：数字与字符串原样保留（若改用 JSON.parse + stringify，这里会变成 7494312521977267000 / 1.1 / 1000 / a:2）
  assert.match(r.pretty, /"seller_id": 7494312521977267257/)
  assert.match(r.pretty, /"amount": 1\.10/)
  assert.match(r.pretty, /"exp": 1e3/)
  assert.match(r.pretty, /"a": 1,\n\s+"a": 2/)
  assert.match(r.pretty, /"note": "订单 7494312521977267257 已受理"/)
  // 缩进是 2 空格，且首行为顶层 `{`
  assert.equal(r.pretty.split('\n')[0], '{')
  assert.equal(r.pretty.split('\n')[1], '  "seller_id": 7494312521977267257,')
})

test('字符串内含 { } , : 与转义引号、反斜杠：不误判结构', () => {
  const raw = '{"a":"{not:structure}, \\"quoted\\" and \\\\ backslash","b":[1,2]}'
  const r = analyzePayload(raw)
  const lines = r.pretty.split('\n')
  assert.equal(lines[0], '{')
  assert.equal(lines[1], '  "a": "{not:structure}, \\"quoted\\" and \\\\ backslash",')
  assert.equal(lines[2], '  "b": [')
  assert.equal(lines[3], '    1,')
  assert.equal(lines[4], '    2')
  assert.equal(lines[5], '  ]')
  assert.equal(lines[6], '}')
})

test('空容器不展开：{} / [] / [ ] 保持一行', () => {
  assert.equal(analyzePayload('{"a":{},"b":[]}').pretty, '{\n  "a": {},\n  "b": []\n}')
  assert.equal(analyzePayload('[ ]').pretty, '[]')
})

test('已格式化的 JSON 再美化：空白归一，token 不变', () => {
  const pretty = '{\n    "a":  1,\n\n    "b" : 2\n}'
  assert.equal(analyzePayload(pretty).pretty, '{\n  "a": 1,\n  "b": 2\n}')
})

test('截断报文：剥离后缀（两种形态）后仍可缩进并标记已截断', () => {
  const raw = '{"a":1,"b":2,"c":"半截...[truncated]'
  const r = analyzePayload(raw)
  assert.equal(r.truncated, true)
  assert.equal(r.lang, 'json')
  assert.equal(r.formatted, true)
  assert.ok(r.note.includes('已截断'))
  // 截断后的半截字符串标记保留在输出中（不做任何“修复”）
  assert.match(r.pretty, /"c": "半截/)

  const previewSuffix = analyzePayload('{"x":1}…[truncated]')
  assert.equal(previewSuffix.truncated, true)
  assert.equal(previewSuffix.pretty, '{\n  "x": 1\n}')
})

test('超大报文不美化（tooLarge），仍可看原文', () => {
  const huge = '{"a":"' + 'x'.repeat(MAX_FORMAT_CHARS) + '"}'
  const r = analyzePayload(huge)
  assert.equal(r.tooLarge, true)
  assert.equal(r.formatted, false)
  assert.equal(r.raw, huge)
  assert.ok(r.note.includes('过大'))
})

// ---------- XML ----------

test('XML：嵌套 / 属性 / 自闭合 / 注释 / CDATA 缩进，标签与属性原样', () => {
  const raw = '<?xml version="1.0" encoding="UTF-8"?><root a="1" b=\'2\'><item id="x"/><item>text</item><!--注释--><![CDATA[<raw> & data]]></root>'
  const r = analyzePayload(raw)
  assert.equal(r.lang, 'xml')
  assert.equal(r.formatted, true)
  const lines = r.pretty.split('\n')
  assert.equal(lines[0], '<?xml version="1.0" encoding="UTF-8"?>')
  assert.equal(lines[1], '<root a="1" b=\'2\'>')
  assert.equal(lines[2], '  <item id="x"/>')
  assert.equal(lines[3], '  <item>text</item>')
  assert.equal(lines[4], '  <!--注释-->')
  assert.equal(lines[5], '  <![CDATA[<raw> & data]]>')
  assert.equal(lines[6], '</root>')
})

test('XML：纯空白文本节点丢弃，混合内容保持同行', () => {
  const r = analyzePayload('<a>\n  <b>Hello <i>world</i></b>\n</a>')
  const lines = r.pretty.split('\n')
  assert.equal(lines[0], '<a>')
  assert.equal(lines[1], '  <b>Hello <i>world</i></b>')
  assert.equal(lines[2], '</a>')
})

test('XML：含 ">" 的属性值与 DOCTYPE 内部子集不被截断', () => {
  const raw = '<!DOCTYPE note [<!ENTITY x "y">]><note cond="a>b">hi</note>'
  const r = analyzePayload(raw)
  const lines = r.pretty.split('\n')
  assert.equal(lines[0], '<!DOCTYPE note [<!ENTITY x "y">]>')
  assert.equal(lines[1], '<note cond="a>b">hi</note>')
})

// ---------- 降级与探测 ----------

test('非 JSON/XML 一律降级为原文（form-urlencoded / 纯文本 / 二进制乱码）', () => {
  const form = analyzePayload('a=1&b=2')
  assert.equal(form.lang, 'text')
  assert.equal(form.formatted, false)
  assert.equal(form.pretty, form.raw)

  const text = analyzePayload('供应商返回：ok')
  assert.equal(text.formatted, false)

  const binary = analyzePayload('\u0000\u0001PK\u0003\u0004')
  assert.equal(binary.formatted, false)
  assert.equal(binary.pretty, binary.raw)
})

test('空值 / null / 空白：空态且不报错', () => {
  for (const v of [null, undefined, '', '   ']) {
    const r = analyzePayload(v)
    assert.equal(r.formatted, false)
    assert.equal(r.lang, 'text')
    assert.equal(r.pretty, '')
  }
})

test('探测函数边界', () => {
  assert.equal(isJsonLike('{"a":1}'), true)
  assert.equal(isJsonLike('[1]'), true)
  assert.equal(isJsonLike('（中文）'), false)
  assert.equal(isXmlLike('<a>'), true)
  assert.equal(isXmlLike('<?xml version="1.0"?>'), true)
  assert.equal(isXmlLike('<!-- c -->'), true)
  assert.equal(isXmlLike('<!DOCTYPE html>'), true)
  assert.equal(isXmlLike('a < b'), false)
})
