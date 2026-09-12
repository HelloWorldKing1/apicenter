/**
 * 报文格式化单测（零依赖：Node 24 内置 test runner）。
 * 运行：`npm test`（= node --test src/utils）或 `node --test src/utils/payload.test.mjs`。
 * 文件用 .mjs：与 payload.mjs 同源，Node 直跑无需给 package.json 加 "type": "module"。
 *
 * 核心契约（评审拍板）：**美化只增删空白，不改写任何 token** —— 下面用 19 位数字等断言把它锁死。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  analyzeHeaders, analyzePayload, contentTypeOf, detectBinary, FOLD_LINES, formatForm, formatHeaders,
  highlightEnabled, isFormLike, isJsonLike, isXmlLike, pickPayloadText, splitTokenLines,
  tokenize, tokenizeJson, tokenizeXml, HIGHLIGHT_MAX_CHARS, MAX_FORMAT_CHARS
} from './payload.mjs'

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

test('其他类型降级：form 拆行、纯文本原样、二进制只保留原文', () => {
  // P2：form-urlencoded 也美化（拆行），不再是纯文本
  const form = analyzePayload('a=1&b=2')
  assert.equal(form.lang, 'form')
  assert.equal(form.formatted, true)
  assert.equal(form.pretty, 'a = 1\n  b = 2')

  const text = analyzePayload('供应商返回：ok')
  assert.equal(text.lang, 'text')
  assert.equal(text.formatted, false)
  assert.equal(text.pretty, text.raw)

  // P2：二进制识别后仅提示，不铺屏、原文可复制
  const binary = analyzePayload('\u0000\u0001PK\u0003\u0004')
  assert.equal(binary.lang, 'binary')
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

// ---------- 展示取值（回归：组件正文曾因少写 .value 恒为空） ----------

test('pickPayloadText：美化 / 原文 / 未格式化回退 / 空态', () => {
  const json = analyzePayload('{"a":1}')
  assert.equal(pickPayloadText(json, 'pretty'), '{\n  "a": 1\n}')
  assert.equal(pickPayloadText(json, 'raw'), '{"a":1}')

  const text = analyzePayload('denied')
  assert.equal(pickPayloadText(text, 'pretty'), 'denied')      // 未格式化 → 回退原文
  assert.equal(pickPayloadText(text, 'raw'), 'denied')

  assert.equal(pickPayloadText(analyzePayload(null), 'pretty'), '')
  assert.equal(pickPayloadText(null, 'pretty'), '')
})

test('回归：任何非空报文在默认（美化）模式下展示文本必须非空', () => {
  const samples = [
    '{"a":1,"seller_id":7494312521977267257}',
    'denied',
    '<r><a>1</a></r>',
    '{"a":1,"b":"半截...[truncated]',
    'a=1&b=2',
    '  {"nested":{"deep":[1,2,3]}}  '
  ]
  for (const s of samples) {
    const shown = pickPayloadText(analyzePayload(s), 'pretty')
    assert.notEqual(shown, '', `展示文本为空：${JSON.stringify(s)}`)
  }
})

// ---------- P2：Content-Type 提示 ----------

test('Content-Type 提示参与探测：json / xml / form-urlencoded', () => {
  assert.equal(analyzePayload('{"a":1}', 'application/json; charset=utf-8').lang, 'json')
  assert.equal(analyzePayload('<r><a>1</a></r>', 'text/xml').lang, 'xml')
  // form 无 `&` 的单键值：无提示不美化（避免把普通句子误判），有提示才拆行
  assert.equal(analyzePayload('a=1').lang, 'text')
  assert.equal(analyzePayload('a=1', 'application/x-www-form-urlencoded').lang, 'form')
})

test('contentTypeOf：从脱敏头串取 Content-Type（去参数 / 小写 / 取不到为空）', () => {
  const headers = 'Accept: application/json | Content-Type: application/json; charset=utf-8 | X-Trace-Id: t'
  assert.equal(contentTypeOf(headers), 'application/json')
  assert.equal(contentTypeOf('X-A: 1 | X-B: 2'), '')
  assert.equal(contentTypeOf(null), '')
})

// ---------- P2：form-urlencoded ----------

test('form：探测从严、拆行保序、值做百分号解码', () => {
  assert.equal(isFormLike('a=1&b=2', false), true)
  assert.equal(isFormLike('a=1', false), false)
  assert.equal(isFormLike('a=1', true), true)
  assert.equal(isFormLike('订单 a=b 已受理', false), false)

  assert.equal(formatForm('name=%E5%BC%A0%E4%B8%89&age=18'), 'name = 张三\n  age = 18')
  const r = analyzePayload('a=1&b=2')
  assert.equal(r.lang, 'form')
  assert.equal(r.formatted, true)
  assert.equal(r.pretty, 'a = 1\n  b = 2')
})

// ---------- P2：HTTP 头串拆行 ----------

test('headers：按「 | + 头名:」拆行；值内含 " | " 不误拆', () => {
  const raw = 'Accept: application/json | Content-Type: application/json | X-Note: a | b | X-Trace-Id: tt'
  assert.equal(formatHeaders(raw),
    'Accept: application/json\nContent-Type: application/json\nX-Note: a | b\nX-Trace-Id: tt')

  const view = analyzeHeaders(raw)
  assert.equal(view.lang, 'headers')
  assert.equal(view.formatted, true)
  assert.equal(view.pretty.split('\n').length, 4)

  const single = analyzeHeaders('Accept: application/json')
  assert.equal(single.formatted, false)   // 单行头串无需美化，不显示切换
})

// ---------- P2：二进制识别 ----------

test('二进制：控制字符 / NUL 判为 binary；长纯文本与长 token 不误判', () => {
  assert.equal(detectBinary('\u0000\u0001PK\u0003\u0004abc', false).binary, true)
  assert.equal(detectBinary('abcdefghij'.repeat(20), false).binary, false)          // 长纯文本
  assert.equal(detectBinary('sk-' + 'a1B2c3D4'.repeat(12), false).binary, false)    // 长 token
  const bin = analyzePayload('\u0000\u0001PK\u0003\u0004abc')
  assert.equal(bin.lang, 'binary')
  assert.equal(bin.formatted, false)
  assert.ok(bin.note.includes('二进制'))
})

test('Base64 图片：完整内容给内联预览，截断内容只提示不预览', () => {
  const pngBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/wFvpM0AAAAASUVORK5CYII='
  const full = analyzePayload(pngBase64)
  assert.equal(full.lang, 'binary')
  assert.ok(full.note.includes('PNG'))
  assert.ok(full.imagePreview.startsWith('data:image/png;base64,'))

  const cut = analyzePayload(pngBase64 + '...[truncated]')
  assert.equal(cut.imagePreview, null)
  assert.ok(cut.note.includes('不提供预览'))
})

// ---------- P2：语法高亮 tokenizer（无损） ----------

const LOSSLESS_SAMPLES = [
  ['json', '{\n  "a": 1,\n  "s": "含 { } , : \\" 引号",\n  "n": [1, 2.5, -3e4],\n  "b": true,\n  "z": null\n}'],
  ['xml', '<?xml version="1.0"?>\n<r a="1" b=\'2\'>\n  <item>文本 &amp; 符号</item>\n  <!--注释-->\n  <![CDATA[<raw> & data]]>\n  <self x="a>b"/>\n</r>']
]

test('tokenizer 无损：token 文本拼接后与输入逐字节相同', () => {
  for (const [lang, text] of LOSSLESS_SAMPLES) {
    const tokens = tokenize(text, lang)
    assert.equal(tokens.map((t) => t.text).join(''), text, `${lang} token 拼接不一致`)
  }
})

test('JSON token 类型：key 与 string 区分、数字/literal/punct 正确', () => {
  const tokens = tokenizeJson('{"a": "b", "n": 7494312521977267257, "ok": true}')
  const types = tokens.filter((t) => t.type !== 'plain' && t.type !== 'punct')
  assert.deepEqual(
    types.map((t) => `${t.type}:${t.text}`),
    ['key:"a"', 'string:"b"', 'key:"n"', 'number:7494312521977267257', 'key:"ok"', 'literal:true']
  )
})

test('XML token 类型：tag/attr/attrvalue/comment/cdata/pi/doctype/text', () => {
  const tokens = tokenizeXml('<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x "y">]><r a="1"><!--c--><![CDATA[d]]>t</r>')
  const types = new Set(tokens.map((t) => t.type))
  for (const t of ['pi', 'doctype', 'tag', 'attr', 'attrvalue', 'comment', 'cdata', 'text', 'punct']) {
    assert.ok(types.has(t), `缺少 token 类型 ${t}`)
  }
  assert.ok(tokens.some((t) => t.type === 'tag' && t.text === 'r'))
  assert.ok(tokens.some((t) => t.type === 'attr' && t.text === 'a'))
  assert.ok(tokens.some((t) => t.type === 'attrvalue' && t.text === '"1"'))
})

test('splitTokenLines：按行切分（含跨行 CDATA），行拼接 = 原文去换行', () => {
  const lines = splitTokenLines(tokenize('a\nbb\n\nc', 'text'))
  assert.equal(lines.length, 4)
  assert.deepEqual(lines.map((l) => l.map((t) => t.text).join('')), ['a', 'bb', '', 'c'])

  const xml = '<r><![CDATA[line1\nline2]]></r>'
  const xmlLines = splitTokenLines(tokenize(xml, 'xml'))
  assert.equal(xmlLines.map((l) => l.map((t) => t.text).join('')).join('\n'), xml)
})

test('highlightEnabled：超上限不高亮（仍可缩进）', () => {
  assert.equal(highlightEnabled('x'.repeat(HIGHLIGHT_MAX_CHARS)), true)
  assert.equal(highlightEnabled('x'.repeat(HIGHLIGHT_MAX_CHARS + 1)), false)
  assert.equal(highlightEnabled(''), false)
})

test('常量口径：折叠行数与渲染上限有序', () => {
  assert.ok(FOLD_LINES > 0 && FOLD_LINES < 1000)
  assert.ok(MAX_FORMAT_CHARS > HIGHLIGHT_MAX_CHARS)
})
