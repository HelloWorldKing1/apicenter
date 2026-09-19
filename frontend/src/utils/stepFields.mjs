/**
 * 前置步骤输出 → 字段映射 source 的可选分组（2026-09-18，编排 PS-7 补缺）。
 *
 * 背景：前置步骤的输出挂在保留命名空间 `steps.<步骤名>.<字段>`，但字段映射的 source 下拉原先只列
 * 「入站参数」，用户「选不到 steps.fm.total」（只能手输且无提示）。本模块把「宿主的前置步骤配置」
 * 与「各前置接口的字段声明」合成下拉分组，供 Interfaces.vue 渲染。
 *
 * 纯函数（无 Vue / 无网络）：便于单测覆盖分组、去重、停用步骤、声明缺失四种情形。
 */

/**
 * @param {Array} steps 宿主配置的前置步骤：[{ stepCode, targetInterfaceId, enabled, targetCode? }]
 * @param {Object} declarations 前置接口字段声明：{ [targetInterfaceId]: { resp: string[], out: string[] } }
 *        - resp = RESP 出站响应字段（fieldDefs.kind === 'RESP'）
 *        - out  = 出站侧参数（params.side === 'OUT'）
 *        （两者都是「前置步骤执行完能引用到的字段」来源；未读到时该步骤归入 unknown）
 * @returns {{ groups: Array<{key: string, label: string, options: string[]}>, unknown: Array<{stepCode: string, targetInterfaceId: *}> }}
 */
export function buildStepFieldGroups(steps, declarations) {
  const groups = []
  const unknown = []
  const seen = new Set()
  for (const step of steps || []) {
    if (!step || !step.stepCode) {
      continue
    }
    if (step.enabled === false) {
      continue // 停用的步骤不执行，引用也拿不到值（避免误导）
    }
    if (seen.has(step.stepCode)) {
      continue // 同名前缀不重复成组（保存期已拦截重复，这里兜底）
    }
    seen.add(step.stepCode)
    const decl = (declarations || {})[String(step.targetInterfaceId)]
    // 去重保序：RESP 先、OUT 后（与「响应字段更常被引用」的直觉一致）
    const names = [...new Set([...(decl?.resp || []), ...(decl?.out || [])])].filter((n) => n && !n.includes('steps'))
    if (!names.length) {
      unknown.push({ stepCode: step.stepCode, targetInterfaceId: step.targetInterfaceId })
      continue
    }
    groups.push({
      key: step.stepCode,
      label: `前置步骤 · ${step.stepCode}${step.targetCode ? `（${step.targetCode}）` : ''}`,
      options: names.map((name) => `steps.${step.stepCode}.${name}`)
    })
  }
  return { groups, unknown }
}
