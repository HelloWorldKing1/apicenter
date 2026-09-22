/**
 * 鉴权适配器的「用途」判定（2026-09-22）—— 用于应用弹窗**两个下拉的互相过滤**。
 *
 * <p>背景（真实踩坑）：应用弹窗有「**供应商签名适配器**」（出站，凭证 `kind=OUTBOUND`）与
 * 「**回调验签适配器**」（入站回调，凭证 `kind=CALLBACK`）两个下拉，此前**共用同一份列表、标签完全一样**
 * ⇒ 把「HMAC 回调验签」选进「供应商签名」**完全可能且静默**：凭证被存成 `OUTBOUND`、回调验签为空，
 * 直到点「模拟回调」才报 `应用未配置回调验签凭证（CALLBACK）` —— 报的是症状，看不出是选错了下拉。
 *
 * <p>口径：**只认已知 impl**；未知 / 自定义 impl **两侧都允许**（不限制平台未知的实现类），
 * 这样新增 impl 不会被误挡。
 */

/** 只该出现在「回调验签」侧 */
const CALLBACK_ONLY = new Set(['HmacCallbackVerifyAdapter'])

/** 只该出现在「供应商签名（出站）」侧 */
const OUTBOUND_ONLY = new Set(['BearerTokenAuthAdapter', 'ApiKeyAuthAdapter'])

/**
 * 该 impl 是否可用于指定角色。
 * @param {string} impl 适配器实现类（如 `HmacCallbackVerifyAdapter`）
 * @param {'OUTBOUND'|'CALLBACK'} role 凭证角色（= 界面上的两个下拉）
 */
export function adapterMatchesRole(impl, role) {
  if (!impl) return false
  if (CALLBACK_ONLY.has(impl)) return role === 'CALLBACK'
  if (OUTBOUND_ONLY.has(impl)) return role === 'OUTBOUND'
  return true
}

/** 给下拉标签用的短提示（让"这个适配器该放哪一侧"在界面上可见） */
export function adapterRoleHint(impl) {
  if (CALLBACK_ONLY.has(impl)) return '仅回调验签'
  if (OUTBOUND_ONLY.has(impl)) return '仅出站签名'
  return '两者皆可'
}
