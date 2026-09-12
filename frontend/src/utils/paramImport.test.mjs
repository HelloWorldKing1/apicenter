/**
 * 请求参数快速导入单测（Node 24 内置 test runner，零依赖）。
 * 运行：`npm test`（= test:unit + test:ssr），或单独 `node --test src/utils/paramImport.test.mjs`。
 *
 * 核心契约：示例值取 **token 原始切片**（19 位数字 / 1.10 / 1e3 逐字节保真）；
 * 路径形态：嵌套 `.`、数组元素 `[0]`、特殊键名 `["a.b"]`。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { extractParams, locateJsonError, scanStructureIssue, MAX_IMPORT_PARAMS } from './paramImport.mjs'

const SAMPLE = `{
  "filter": { "seller_id": 7494312521977267257, "orderby": [{"field":"units_sold","order":"desc"}] },
  "page": 1, "pagesize": 10, "debug": false, "note": null, "tags": [], "meta": {}
}`

test('嵌套 + 数组取样：路径/类型/必填/顺序', () => {
  const r = extractParams(SAMPLE)
  assert.equal(r.ok, true)
  assert.equal(r.format, 'json')
  assert.deepEqual(
    r.params.map((p) => `${p.name}|${p.type}|${p.required ? 'Y' : 'N'}`),
    [
      'filter|object|Y',
      'filter.seller_id|number|Y',
      'filter.orderby|array|Y',
      'filter.orderby[0]|object|Y',
      'filter.orderby[0].field|string|Y',
      'filter.orderby[0].order|string|Y',
      'page|number|Y',
      'pagesize|number|Y',
      'debug|boolean|Y',
      'note|string|N',
      'tags|array|N',
      'meta|object|N'
    ]
  )
  assert.equal(r.stats.maxDepth, 4)
})

test('示例值保真：19 位数字 / 1.10 / 1e3 均为原始字面量', () => {
  const r = extractParams('{"id":7494312521977267257,"amount":1.10,"exp":1e3,"s":"a\\"b"}')
  const byName = Object.fromEntries(r.params.map((p) => [p.name, p]))
  assert.equal(byName.id.sample, '7494312521977267257')
  assert.equal(byName.amount.sample, '1.10')
  assert.equal(byName.exp.sample, '1e3')
  assert.equal(byName.s.sample, '"a\\"b"')
})

test('null 与空容器：保守为非必填（null 示例留空）', () => {
  const r = extractParams('{"a":null,"b":{},"c":[]}')
  const byName = Object.fromEntries(r.params.map((p) => [p.name, p]))
  assert.equal(byName.a.type, 'string')
  assert.equal(byName.a.required, false)
  assert.equal(byName.a.sample, '')
  assert.equal(byName.b.required, false)
  assert.equal(byName.b.sample, '{}')
  assert.equal(byName.c.required, false)
  assert.equal(byName.c.sample, '[]')
})

test('数组样本：容器行报项数，元素路径带 [0]', () => {
  const r = extractParams('{"list":[{"id":1},{"id":2},{"id":3}]}')
  assert.deepEqual(r.params.map((p) => `${p.name}|${p.type}|${p.sample}`), [
    'list|array|[3 项]',
    'list[0]|object|{"id":1}',
    'list[0].id|number|1'
  ])
})

test('顶层是数组：按 [0] 展开', () => {
  const r = extractParams('[{"sku":"A","qty":2}]')
  assert.equal(r.ok, true)
  assert.deepEqual(r.params.map((p) => p.name), ['[0]', '[0].sku', '[0].qty'])
})

test('选项：不展开嵌套 / 不取数组样本 / 仅顶层必填', () => {
  const flat = extractParams(SAMPLE, { expand: false })
  assert.deepEqual(flat.params.map((p) => p.name), ['filter', 'page', 'pagesize', 'debug', 'note', 'tags', 'meta'])

  const noSample = extractParams('{"list":[{"id":1}]}', { arraySample: false })
  assert.deepEqual(noSample.params.map((p) => p.name), ['list'])

  const topOnly = extractParams(SAMPLE, { allRequired: false })
  const nested = topOnly.params.filter((p) => p.name.includes('.') || p.name.includes('['))
  assert.ok(nested.length > 0)
  assert.ok(nested.every((p) => p.required === false), '嵌套参数应全部为非必填')
  assert.equal(topOnly.params.find((p) => p.name === 'page').required, true)
})

test('限额：超深度折叠提示、超条数截断并计数', () => {
  const deep = extractParams('{"a":{"b":{"c":{"d":{"e":{"f":{"g":1}}}}}}}', { maxDepth: 3 })
  assert.ok(deep.warnings.some((w) => w.includes('嵌套超过 3 层')))
  assert.equal(deep.params.find((p) => p.name === 'a.b.c').type, 'object')

  const many = JSON.stringify(Object.fromEntries(Array.from({ length: 260 }, (_, i) => [`k${i}`, i])))
  const capped = extractParams(many)
  assert.equal(capped.params.length, MAX_IMPORT_PARAMS)
  assert.ok(capped.stats.skipped >= 60)
  assert.ok(capped.warnings.some((w) => w.includes('参数条数超过上限')))
})

test('重复键：后者生效并提示（与 JSON 语义一致）', () => {
  const r = extractParams('{"a":1,"a":2}')
  assert.equal(r.params.length, 1)
  assert.equal(r.params[0].sample, '2')
  assert.ok(r.warnings.some((w) => w.includes('重复键')))
})

test('特殊键名：点号/方括号/空格用 ["键"] 包裹；空键名跳过', () => {
  const r = extractParams('{"a.b":"x","c d":"y","":{"z":1},"tail":2}')
  const names = r.params.map((p) => p.name)
  // 空键名整棵子树跳过，且不能把子树里的键误当兄弟键（tail 必须仍在）
  assert.deepEqual(names, ['["a.b"]', '["c d"]', 'tail'])
  assert.ok(r.warnings.some((w) => w.includes('包裹以免路径歧义')))
  assert.ok(r.warnings.some((w) => w.includes('空键名')))
})

test('form-urlencoded：解码、类型推断、重复键后者生效', () => {
  const r = extractParams('name=%E5%BC%A0%E4%B8%89&age=18&ok=true&bad=%E4%B8&age=20')
  assert.equal(r.format, 'form')
  assert.deepEqual(r.params.map((p) => `${p.name}|${p.type}|${p.sample}`), [
    'name|string|张三',
    'age|number|20',
    'ok|boolean|true',
    'bad|string|%E4%B8'   // 非法百分号编码 → 保留原值
  ])
})

test('语法错误：结构扫描给出行列与原因（未闭合 / 括号不匹配 / 顶层多余内容 / 空输入）', () => {
  const unclosed = extractParams('{"a":1,"b":[1,2')
  assert.equal(unclosed.ok, false)
  assert.deepEqual(unclosed.params, [])
  assert.ok(unclosed.error.message.includes('内容未闭合'))
  assert.equal(unclosed.error.line, 1)
  assert.ok(unclosed.error.column > 1)

  const mismatch = extractParams('{\n  "a": 1,\n]}')
  assert.ok(mismatch.error.message.includes('括号不匹配'))
  assert.equal(mismatch.error.line, 3)

  const trailing = extractParams('{"a":1} garbage')
  assert.ok(trailing.error.message.includes('多余内容'))

  const blank = extractParams('   ')
  assert.ok(blank.error.message.includes('内容为空'))
})

test('截断：结构完整时标注截断后导入；结构未闭合时明确报错不猜数据', () => {
  const intact = extractParams('{"a":1}...[truncated]')
  assert.equal(intact.truncated, true)
  assert.equal(intact.params.length, 1)
  assert.ok(intact.warnings.some((w) => w.includes('截断')))

  const broken = extractParams('{"a":1,"b":"半截...[truncated]')
  assert.equal(broken.truncated, true)
  assert.equal(broken.ok, false)
  assert.ok(broken.error.message.includes('未闭合'))
  assert.equal(broken.params.length, 0)
})

test('顶层标量：明确报错（不硬猜参数）', () => {
  const r = extractParams('123')
  assert.equal(r.ok, false)
  assert.ok(r.error.message.includes('顶层不是对象或数组'))
})

test('locateJsonError / scanStructureIssue：正确定位未闭合与不匹配', () => {
  assert.equal(scanStructureIssue('{"a":1}'), null)
  assert.equal(scanStructureIssue('{"a":1}garbage').message, '顶层结构已结束但后面还有多余内容')
  const e = locateJsonError('Unexpected token } in JSON at position 12', '{\n  "a": 1,\n}')
  assert.equal(e.line, 3)
})
