/**
 * 入站鉴权平台设置 / 凭证池的**界面口径**（2026-09-24 v1.2）。
 *
 * <p>纯函数集中在这里，便于单测：页面只管渲染与调用，判断口径不散落在模板里。
 */

/** 平台设置的展示快照（与后端 `SettingView` 对应） */
export const EMPTY_SETTING = {
  defaultAdapterId: null,
  defaultAdapterName: null,
  requireClientId: true,
  updatedBy: null,
  updatedAt: null
}

/**
 * 是否属于「**放松类**」变更 —— 需要二次确认 + 后端也会落 WARN 告警。
 *
 * <p>两类：① `requireClientId` 由 1 变 0（主体不再强制）；
 * ② **鉴权方式发生变化**（宽严无法自动判定，故一律按放松处理 —— 宁可多一次确认，不可漏一次放宽）。
 *
 * @param {{defaultAdapterId?: string|null, requireClientId?: boolean}} before 变更前
 * @param {{defaultAdapterId?: string|null, requireClientId?: boolean}} after 变更后
 * @returns {{relaxation: boolean, reasons: string[]}}
 */
export function diffSetting(before, after) {
  const reasons = []
  const b = before || EMPTY_SETTING
  const a = after || EMPTY_SETTING
  if (b.requireClientId === true && a.requireClientId === false) {
    reasons.push('取消「强制自报主体」：未带主体标识的请求将不再被拒（开放集语义）')
  }
  if ((b.defaultAdapterId || null) !== (a.defaultAdapterId || null)) {
    reasons.push('平台默认鉴权方式发生变化：影响所有未单独绑定鉴权方式的接口')
  }
  return { relaxation: reasons.length > 0, reasons }
}

/** 二次确认文案（放松类变更时展示；含影响面） */
export function relaxationConfirmText(reasons, affectedInterfaces) {
  const lines = [...(reasons || [])]
  if (typeof affectedInterfaces === 'number') {
    lines.push(`受影响接口：${affectedInterfaces} 个未单独绑定鉴权方式的出站中转接口`)
  }
  lines.push('确认继续？此变更会立即生效（无需重启），并会记录操作人。')
  return lines.join('\n')
}

/** 属主类型的展示名（凭证池三级属主） */
export const OWNER_TYPES = [
  { value: 'PLATFORM', label: '平台共享池', hint: '不登记调用方也能接入：把这把密钥发给对方即可' },
  { value: 'INTERFACE', label: '接口专属池', hint: '只对本接口有效（ownerId = 接口数字 id）；有专属凭证时不再看平台池' },
  { value: 'CLIENT', label: '调用方档案池', hint: '需要「可验证身份 / 单独配额」时才登记（可选精确管控）' }
]

export function ownerTypeLabel(value) {
  return OWNER_TYPES.find((t) => t.value === value)?.label || value || '—'
}

/** 该属主是否必须填 ownerId（平台共享池的 owner_id 在库里就是 NULL） */
export function ownerIdRequired(ownerType) {
  return ownerType === 'INTERFACE' || ownerType === 'CLIENT'
}

/** 凭证状态的中文与颜色（轮换并存要看得出来） */
export function credentialStatusLabel(status) {
  if (status === 'ACTIVE') return '当前使用'
  if (status === 'ROTATING') return '轮换并存（待激活/待收尾）'
  if (status === 'RETIRED') return '已失效'
  return status || '—'
}

/** 凭证类型 -> 对应的「方式」名（界面提示用，便于理解 kind 与 adapter 的对应关系） */
export function kindLabel(kind) {
  const map = {
    API_KEY: 'API Key',
    HMAC_SECRET: 'HMAC 签名密钥',
    BEARER_TOKEN: 'Bearer Token',
    BASIC: 'Basic'
  }
  return map[kind] || kind || '—'
}
