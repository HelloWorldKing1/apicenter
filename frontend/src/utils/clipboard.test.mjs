/**
 * 剪贴板工具单测（2026-09-25）。
 *
 * 要点：`navigator.clipboard` 在**非安全上下文**（`http://192.168.x.x:5173` 这类）是 `undefined`，
 * 所以必须有兜底路径 —— 否则「复制」按钮在真实联调环境里直接抛错（只在本机 localhost 好用）。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { copyText } from './clipboard.mjs'

function fakeDoc({ execResult = true, noExecCommand = false } = {}) {
  const removed = []
  const doc = {
    body: {
      appendChild(node) {
        doc._appended = node
      },
      removeChild(node) {
        removed.push(node)
      }
    },
    createElement() {
      return {
        value: '',
        style: {},
        setAttribute() {},
        select() {
          this.selected = true
        },
        remove() {
          removed.push('remove()')
        }
      }
    }
  }
  if (!noExecCommand) {
    doc.execCommand = () => execResult
  }
  doc._removed = removed
  return doc
}

test('优先走 navigator.clipboard', async () => {
  let written = null
  const nav = { clipboard: { writeText: async (v) => { written = v } } }
  assert.equal(await copyText('sk-1', { navigator: nav, document: fakeDoc() }), true)
  assert.equal(written, 'sk-1')
})

test('clipboard 抛错（权限被拒/非安全上下文）→ 回退 execCommand', async () => {
  const doc = fakeDoc({ execResult: true })
  const nav = { clipboard: { writeText: async () => { throw new Error('NotAllowedError') } } }
  assert.equal(await copyText('plain', { navigator: nav, document: doc }), true)
  assert.equal(doc._appended.value, 'plain')
  assert.ok(doc._appended.selected, '临时 textarea 应被全选')
  assert.equal(doc._removed.length, 1, '用完必须从 DOM 移除')
})

test('无 navigator.clipboard（http 访问）→ 直接走兜底', async () => {
  const nav = {}
  assert.equal(await copyText('x', { navigator: nav, document: fakeDoc() }), true)
})

test('兜底也失败 / 环境不具备 → 返回 false，不抛', async () => {
  assert.equal(await copyText('x', { navigator: {}, document: fakeDoc({ execResult: false }) }), false)
  assert.equal(await copyText('x', { navigator: {}, document: fakeDoc({ noExecCommand: true }) }), false)
  // SSR：无 navigator / document（显式传 null 模拟）
  assert.equal(await copyText('x', { navigator: null, document: null }), false)
})

test('空值不做任何事（返回 false）', async () => {
  const nav = { clipboard: { writeText: async () => { throw new Error('不该被调用') } } }
  assert.equal(await copyText('', { navigator: nav, document: fakeDoc() }), false)
  assert.equal(await copyText(null, { navigator: nav, document: fakeDoc() }), false)
  assert.equal(await copyText(undefined, { navigator: nav, document: fakeDoc() }), false)
})

test('非字符串入参按字符串处理（数字 token 场景）', async () => {
  let written = null
  const nav = { clipboard: { writeText: async (v) => { written = v } } }
  assert.equal(await copyText(12345, { navigator: nav, document: fakeDoc() }), true)
  assert.equal(written, '12345')
})
