/**
 * 调用日志上下文归一化单测（2026-09-12 抽公共后补测）。
 * 两种行形态：API 原始行（direction/traceId/appId/interfaceId/statusCode/latencyMs/createdAt）
 * 与 Dashboard 映射行（dir/trace/app/iface/code/ms/time）。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { callLogContext, deadLetterContext, normalizeCallLog, outboundContext } from './logContext.mjs'

test('normalizeCallLog：两种行形态归一', () => {
  const api = normalizeCallLog({
    id: 448, direction: 'OUT', method: 'POST', url: 'http://x/y',
    interfaceId: 302, appId: 'APP1', traceId: 't1', statusCode: 401, latencyMs: 3,
    createdAt: '2026-09-12T10:23:35'
  })
  assert.deepEqual(api, {
    id: 448, direction: 'OUT', method: 'POST', url: 'http://x/y',
    interfaceId: 302, appId: 'APP1', traceId: 't1', statusCode: 401, latencyMs: 3,
    createdAt: '2026-09-12T10:23:35'
  })

  const dash = normalizeCallLog({ id: 1, dir: 'IN', method: 'POST', url: '/a', iface: 5, app: 'A', trace: 't', code: 200, ms: 9, time: '10:00' })
  assert.equal(dash.direction, 'IN')
  assert.equal(dash.interfaceId, 5)
  assert.equal(dash.appId, 'A')
  assert.equal(dash.traceId, 't')
  assert.equal(dash.statusCode, 200)
  assert.equal(dash.latencyMs, 9)
  assert.equal(dash.createdAt, '10:00')
  assert.equal(normalizeCallLog(null).id, undefined)
})

test('callLogContext：首行摘要 + 元数据行（两种形态输出一致）', () => {
  const context = callLogContext({
    id: 448, direction: 'OUT', method: 'POST', url: 'http://localhost:18080/up',
    interfaceId: 302, appId: 'SCT-APP', traceId: 'trace-dead', statusCode: 401, latencyMs: 3,
    createdAt: '2026-09-12T10:23:35'
  })
  const lines = context.split('\n')
  assert.equal(lines[0], '调用日志 #448 OUT POST http://localhost:18080/up')
  assert.match(lines[1], /traceId=trace-dead/)
  assert.match(lines[1], /app=SCT-APP/)
  assert.match(lines[1], /interface=302/)
  assert.match(lines[1], /status=401/)
  assert.match(lines[1], /3ms/)
  assert.match(lines[1], /@2026-09-12T10:23:35/)

  const fromDash = callLogContext({ id: 448, dir: 'OUT', method: 'POST', url: 'u', iface: 302, app: 'SCT-APP', trace: 'trace-dead', code: 401, ms: 3 })
  assert.equal(fromDash.split('\n')[0], '调用日志 #448 OUT POST u')
})

test('死信 / 出站记录上下文', () => {
  assert.equal(deadLetterContext({ id: 7, bizType: 'OUTBOUND', refId: 12, status: 'PENDING', createdAt: 'T' }),
    '死信 #7 OUTBOUND ref=12 状态=PENDING @T')
  assert.equal(outboundContext({ id: 9, status: 'UNKNOWN', bizId: 'b1', traceId: 't1' }),
    '出站记录 #9 状态=UNKNOWN bizId=b1 traceId=t1')
  assert.equal(deadLetterContext(null), '')
  assert.equal(outboundContext(undefined), '')
})
