/**
 * 调用日志「复制含上下文」前缀（2026-09-12 抽公共）：
 * Monitor 与 Dashboard 两处抽屉此前各写一份，字段名还不同（direction/dir、traceId/trace、appId/app、
 * interfaceId/iface、statusCode/code、latencyMs/ms）——这里统一归一化，避免改一处漏一处。
 */

/** 归一化调用日志行（兼容原始 API 行与 Dashboard 映射行） */
export function normalizeCallLog(row) {
  if (!row) return {}
  return {
    id: row.id,
    direction: row.direction ?? row.dir,
    method: row.method,
    url: row.url,
    interfaceId: row.interfaceId ?? row.iface,
    appId: row.appId ?? row.app,
    traceId: row.traceId ?? row.trace,
    statusCode: row.statusCode ?? row.code,
    latencyMs: row.latencyMs ?? row.ms,
    createdAt: row.createdAt ?? row.time
  }
}

/** 调用日志上下文（贴工单用） */
export function callLogContext(row) {
  const r = normalizeCallLog(row)
  const head = `调用日志 #${r.id} ${r.direction} ${r.method} ${r.url}`
  const meta = [`traceId=${r.traceId}`, `app=${r.appId}`, `interface=${r.interfaceId}`,
    `status=${r.statusCode}`, `${r.latencyMs}ms`]
  if (r.createdAt) meta.push(`@${r.createdAt}`)
  return `${head}\n${meta.join(' ')}`
}

/** 死信上下文 */
export function deadLetterContext(row) {
  if (!row) return ''
  return `死信 #${row.id} ${row.bizType} ref=${row.refId} 状态=${row.status} @${row.createdAt}`
}

/** 出站记录上下文（状态机 Tab） */
export function outboundContext(row) {
  if (!row) return ''
  return `出站记录 #${row.id} 状态=${row.status} bizId=${row.bizId} traceId=${row.traceId}`
}
