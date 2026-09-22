import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  buildProtocolParams,
  protocolParamsForPayload,
  parseProtocolParams,
  isValidXmlName,
  needsProtocolParams,
  isSoapType,
  rootFieldLabel,
  XML_TYPES,
  XML_VERSIONS,
  XML_ENCODINGS,
} from './protocolParams.mjs'

test('全默认 → 返回 null（不提交 = 库保持 NULL = 从未配置）', () => {
  assert.equal(buildProtocolParams({}), null)
  assert.equal(buildProtocolParams({ version: '1.0', encoding: 'UTF-8', root: '', nsPrefix: '', nsUri: '' }), null)
  assert.equal(buildProtocolParams({ version: '1.0', encoding: 'UTF-8', root: '   ' }), null)
})

test('只输出非空键：默认值不写进 JSON（避免「键存在但为空」被后端 40001）', () => {
  assert.deepEqual(JSON.parse(buildProtocolParams({ version: '1.1', encoding: 'UTF-8' })), { xml: { version: '1.1' } })
  assert.deepEqual(JSON.parse(buildProtocolParams({ encoding: 'GBK' })), { xml: { encoding: 'GBK' } })
  assert.deepEqual(JSON.parse(buildProtocolParams({ root: 'QueryRequest' })), { xml: { root: 'QueryRequest' } })
})

test('命名空间：无 uri 则不输出 namespace（prefix 单独存在视为无配置 → null）', () => {
  assert.equal(buildProtocolParams({ nsPrefix: 'ns' }), null)
  // 实际报告场景（2026-09-22）：只填前缀、不填 URI → 前缀被忽略（含非法前缀也如此）。
  // 正因为这条规则会让用户“填了却没保存”，界面已改为：URI 为空时**禁用前缀输入**。
  assert.equal(buildProtocolParams({ nsPrefix: 'a b' }), null)
  assert.equal(buildProtocolParams({ root: 'Q', nsPrefix: 'a b' }), JSON.stringify({ xml: { root: 'Q' } }))
  assert.deepEqual(JSON.parse(buildProtocolParams({ nsUri: 'http://x' })), { xml: { namespace: { uri: 'http://x' } } })
  assert.deepEqual(JSON.parse(buildProtocolParams({ nsUri: 'http://x', nsPrefix: 'ns' })),
    { xml: { namespace: { uri: 'http://x', prefix: 'ns' } } })
})

/** parse() 的完整默认形态（B2 起含 type 与 soap 子项） */
const FULL_DEFAULTS = {
  type: 'POX', version: '1.0', encoding: 'UTF-8', root: '', nsPrefix: '', nsUri: '',
  action: '', envelopePrefix: 'soap', unwrapResponse: true,
}

test('往返：build → parse 保真（POX 形态）', () => {
  const src = { ...FULL_DEFAULTS, version: '1.1', encoding: 'GB18030', root: 'Add', nsPrefix: 'ns', nsUri: 'http://tempuri.org/' }
  assert.deepEqual(parseProtocolParams(buildProtocolParams(src)), src)
})

test('parse：空/非法/非对象 → 全默认，不抛异常', () => {
  for (const bad of [null, undefined, '', '   ', '{不是JSON', '[1]', '{"xml":null}', '{"xml":"x"}']) {
    assert.deepEqual(parseProtocolParams(bad), FULL_DEFAULTS, `输入=${bad}`)
  }
})

test('parse：缺字段各自回落默认', () => {
  assert.deepEqual(parseProtocolParams('{"xml":{"root":"X"}}'), { ...FULL_DEFAULTS, root: 'X' })
})

test('isValidXmlName：禁冒号/空格/数字开头（与后端 NCName 一致）', () => {
  for (const ok of ['request', 'QueryRequest', '_a', 'a-1', 'a.b']) {
    assert.equal(isValidXmlName(ok), true, ok)
  }
  for (const bad of ['ns:QueryRequest', 'a b', '1abc', 'a<b', '', '请求']) {
    assert.equal(isValidXmlName(bad), false, bad)
  }
})

test('needsProtocolParams：**只看 protocol_out**（入站 XML / 出站 JSON → 不需要）', () => {
  assert.equal(needsProtocolParams('XML'), true)
  assert.equal(needsProtocolParams('JSON'), false)
  assert.equal(needsProtocolParams(undefined), false)
  // 回归：OUTBOUND + in=XML / out=JSON 曾误判为“需要”（界面能配但永不生效）——已修正
  assert.equal(protocolParamsForPayload('JSON', { root: 'X' }), null)
})

test('protocolParamsForPayload：非 XML 出站恒为 null（隐藏块残留值不得下发）', () => {
  const filled = { version: '1.1', encoding: 'GBK', root: 'QueryRequest', nsPrefix: 'ns', nsUri: 'http://x' }
  // XML 出站 → 正常构建
  assert.equal(protocolParamsForPayload('XML', filled),
    '{"xml":{"version":"1.1","encoding":"GBK","root":"QueryRequest","namespace":{"uri":"http://x","prefix":"ns"}}}')
  // 先配 XML 再切回 JSON：表单里仍有残留值，但必须返回 null
  assert.equal(protocolParamsForPayload('JSON', filled), null)
})

test('字符集白名单不含 UTF-16/UTF-32（与后端拒绝口径一致）', () => {
  assert.deepEqual(XML_VERSIONS, ['1.0', '1.1'])
  for (const enc of XML_ENCODINGS) {
    assert.ok(!enc.startsWith('UTF-16') && !enc.startsWith('UTF-32'), enc)
  }
})

// ---------- B2：XML 类型（唯一真相）+ SOAP 子项 ----------

test('XML_TYPES：三项且标签已拍板（Atom/RSS/rpc-encoded 不列）', () => {
  assert.deepEqual(XML_TYPES.map((t) => t.value), ['POX', 'SOAP_1_1', 'SOAP_1_2'])
  assert.deepEqual(XML_TYPES.map((t) => t.label), ['普通 XML（POX）', 'SOAP 1.1', 'SOAP 1.2'])
  assert.equal(isSoapType('POX'), false)
  assert.ok(isSoapType('SOAP_1_1') && isSoapType('SOAP_1_2'))
  assert.equal(rootFieldLabel('POX'), '根元素')
  assert.equal(rootFieldLabel('SOAP_1_1'), 'Body 内业务元素')
})

test('POX：不输出 type / 不输出 soap（后端互斥 + 零迁移）', () => {
  assert.equal(buildProtocolParams({ type: 'POX' }), null)
  assert.deepEqual(JSON.parse(buildProtocolParams({ type: 'POX', root: 'QueryRequest' })),
    { xml: { root: 'QueryRequest' } })   // ← 无 type、无 soap
  // 显式 type=POX 也不写进去（缺省即 POX）
  assert.equal(buildProtocolParams({ type: 'POX', version: '1.0', encoding: 'UTF-8' }), null)
})

test('SOAP：输出 type；soap 段仅非默认项才出现（全默认 → 整段省略）', () => {
  assert.deepEqual(JSON.parse(buildProtocolParams({ type: 'SOAP_1_1', root: 'Add' })),
    { xml: { type: 'SOAP_1_1', root: 'Add' } })      // 无 soap 段（后端用默认）
  assert.deepEqual(JSON.parse(buildProtocolParams({
    type: 'SOAP_1_2', root: 'Add', action: 'http://tempuri.org/Add',
    envelopePrefix: 'env', unwrapResponse: false,
  })),
  { xml: { type: 'SOAP_1_2', root: 'Add', soap: { action: 'http://tempuri.org/Add', envelopePrefix: 'env', unwrapResponse: false } } })
  // envelopePrefix 为默认 soap、unwrap 为默认开 → 都不输出
  assert.deepEqual(JSON.parse(buildProtocolParams({ type: 'SOAP_1_1', envelopePrefix: 'soap', unwrapResponse: true })),
    { xml: { type: 'SOAP_1_1' } })
})

test('SOAP 往返：build → parse 保真（含 action/前缀/解包关）', () => {
  const src = {
    type: 'SOAP_1_2', version: '1.1', encoding: 'GBK', root: 'Add',
    nsPrefix: 'ns', nsUri: 'http://tempuri.org/',
    action: 'http://tempuri.org/Add', envelopePrefix: 'env', unwrapResponse: false,
  }
  assert.deepEqual(parseProtocolParams(buildProtocolParams(src)), src)
})

test('parse：B1 历史数据（无 type）视为 POX（零迁移）', () => {
  const r = parseProtocolParams('{"xml":{"version":"1.1","root":"QueryRequest"}}')
  assert.equal(r.type, 'POX')
  assert.equal(r.version, '1.1')
  assert.equal(r.root, 'QueryRequest')
  assert.equal(r.envelopePrefix, 'soap')   // 未涉及的子项取默认
  assert.equal(r.unwrapResponse, true)
})
