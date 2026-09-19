import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildStepFieldGroups } from './stepFields.mjs'

/** 前置步骤输出 → 映射 source 分组（编排 PS-7 补缺） */

const decls = {
  7: { resp: ['total', 'list'], out: ['seller_id'] },
  9: { resp: ['id'], out: [] }
}

test('正常分组：RESP 先 OUT 后，路径前缀为 steps.<步骤名>.', () => {
  const { groups, unknown } = buildStepFieldGroups(
    [{ stepCode: 'fm', targetInterfaceId: 7, targetCode: 'IF-FM-001', enabled: true }], decls)
  assert.equal(unknown.length, 0)
  assert.equal(groups.length, 1)
  assert.equal(groups[0].key, 'fm')
  assert.equal(groups[0].label, '前置步骤 · fm（IF-FM-001）')
  assert.deepEqual(groups[0].options, ['steps.fm.total', 'steps.fm.list', 'steps.fm.seller_id'])
})

test('多步骤：按配置顺序成组，互不混入', () => {
  const { groups } = buildStepFieldGroups([
    { stepCode: 'fm', targetInterfaceId: 7, enabled: true },
    { stepCode: 'gen', targetInterfaceId: 9, enabled: true }
  ], decls)
  assert.deepEqual(groups.map((g) => g.key), ['fm', 'gen'])
  assert.deepEqual(groups[1].options, ['steps.gen.id'])
})

test('停用的步骤不出现（引用也拿不到值）', () => {
  const { groups, unknown } = buildStepFieldGroups(
    [{ stepCode: 'fm', targetInterfaceId: 7, enabled: false }], decls)
  assert.equal(groups.length, 0)
  assert.equal(unknown.length, 0)
})

test('字段声明未读到（未发布/已删除）→ 归入 unknown 供界面提示手输', () => {
  const { groups, unknown } = buildStepFieldGroups(
    [{ stepCode: 'fm', targetInterfaceId: 7, enabled: true }], {})
  assert.equal(groups.length, 0)
  assert.deepEqual(unknown, [{ stepCode: 'fm', targetInterfaceId: 7 }])
})

test('声明为空数组同样归入 unknown（避免出现空分组）', () => {
  const { groups, unknown } = buildStepFieldGroups(
    [{ stepCode: 'fm', targetInterfaceId: 7, enabled: true }], { 7: { resp: [], out: [] } })
  assert.equal(groups.length, 0)
  assert.equal(unknown[0].stepCode, 'fm')
})

test('字段名去重（RESP 与 OUT 同名只出现一次）', () => {
  const { groups } = buildStepFieldGroups(
    [{ stepCode: 'x', targetInterfaceId: 1, enabled: true }], { 1: { resp: ['a', 'a'], out: ['a', 'b'] } })
  assert.deepEqual(groups[0].options, ['steps.x.a', 'steps.x.b'])
})

test('含点号的嵌套字段照原样拼路径（如 filter.seller_id）', () => {
  const { groups } = buildStepFieldGroups(
    [{ stepCode: 'q', targetInterfaceId: 2, enabled: true }], { 2: { resp: [], out: ['filter.seller_id'] } })
  assert.deepEqual(groups[0].options, ['steps.q.filter.seller_id'])
})

test('空/异常输入不抛错（无步骤、无声明、缺 stepCode）', () => {
  assert.deepEqual(buildStepFieldGroups(null, null), { groups: [], unknown: [] })
  assert.deepEqual(buildStepFieldGroups(undefined, undefined), { groups: [], unknown: [] })
  const { groups } = buildStepFieldGroups([{ targetInterfaceId: 7 }, null], decls)
  assert.equal(groups.length, 0)
})

test('数字与字符串形态的 targetInterfaceId 都能命中声明（不改调用方约定）', () => {
  const { groups } = buildStepFieldGroups(
    [{ stepCode: 'fm', targetInterfaceId: '7', enabled: true }], decls)
  assert.deepEqual(groups[0].options, ['steps.fm.total', 'steps.fm.list', 'steps.fm.seller_id'])
})

test('含 steps 字样的字段名被过滤（保留命名空间自身不参与引用）', () => {
  const { groups } = buildStepFieldGroups(
    [{ stepCode: 'x', targetInterfaceId: 3, enabled: true }], { 3: { resp: ['steps'], out: ['ok'] } })
  assert.deepEqual(groups[0].options, ['steps.x.ok'])
})
