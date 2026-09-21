import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  buildProtocolParams,
  parseProtocolParams,
  isValidXmlName,
  needsProtocolParams,
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
  assert.deepEqual(JSON.parse(buildProtocolParams({ nsUri: 'http://x' })), { xml: { namespace: { uri: 'http://x' } } })
  assert.deepEqual(JSON.parse(buildProtocolParams({ nsUri: 'http://x', nsPrefix: 'ns' })),
    { xml: { namespace: { uri: 'http://x', prefix: 'ns' } } })
})

test('往返：build → parse 保真', () => {
  const src = { version: '1.1', encoding: 'GB18030', root: 'Add', nsPrefix: 'ns', nsUri: 'http://tempuri.org/' }
  assert.deepEqual(parseProtocolParams(buildProtocolParams(src)), src)
})

test('parse：空/非法/非对象 → 全默认，不抛异常', () => {
  const def = { version: '1.0', encoding: 'UTF-8', root: '', nsPrefix: '', nsUri: '' }
  for (const bad of [null, undefined, '', '   ', '{不是JSON', '[1]', '{"xml":null}', '{"xml":"x"}']) {
    assert.deepEqual(parseProtocolParams(bad), def, `输入=${bad}`)
  }
})

test('parse：缺字段各自回落默认', () => {
  assert.deepEqual(parseProtocolParams('{"xml":{"root":"X"}}'),
    { version: '1.0', encoding: 'UTF-8', root: 'X', nsPrefix: '', nsUri: '' })
})

test('isValidXmlName：禁冒号/空格/数字开头（与后端 NCName 一致）', () => {
  for (const ok of ['request', 'QueryRequest', '_a', 'a-1', 'a.b']) {
    assert.equal(isValidXmlName(ok), true, ok)
  }
  for (const bad of ['ns:QueryRequest', 'a b', '1abc', 'a<b', '', '请求']) {
    assert.equal(isValidXmlName(bad), false, bad)
  }
})

test('needsProtocolParams：任一为 XML 即需要', () => {
  assert.equal(needsProtocolParams('XML', 'XML'), true)
  assert.equal(needsProtocolParams('JSON', 'XML'), true)
  assert.equal(needsProtocolParams('XML', 'JSON'), true)
  assert.equal(needsProtocolParams('JSON', 'JSON'), false)
})

test('字符集白名单不含 UTF-16/UTF-32（与后端拒绝口径一致）', () => {
  assert.deepEqual(XML_VERSIONS, ['1.0', '1.1'])
  for (const enc of XML_ENCODINGS) {
    assert.ok(!enc.startsWith('UTF-16') && !enc.startsWith('UTF-32'), enc)
  }
})
