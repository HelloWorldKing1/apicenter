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
 * 只该出现在「**入站鉴权方式**（接口级 CLIENT_AUTH）」侧的 4 个实现（2026-09-24 v1.2）。
 *
 * <p>与上面两个集合的口径差别：这里**只认已知 impl**（未知 impl 在入站鉴权角色下**不允许**）——
 * 因为闸门要求 `instanceof InboundAuthAdapter`，未知实现会被 fail-closed 成 40108；
 * 与其让用户选完才发现不生效，不如在选择器里就不给选。**新增 impl 时必须同步本集合**。
 *
 * <p>注意：这 4 个 impl **仍可**绑到接口的「回调验签（CALLBACK_AUTH）」角色（设计方案 §9.3：
 * 校验逻辑与「是谁的凭证」无关）—— 所以它们只是「不能当出站签名」。
 */
const CLIENT_AUTH_ONLY = new Set([
  'ClientApiKeyVerifyAdapter', 'ClientHmacVerifyAdapter',
  'ClientBearerVerifyAdapter', 'ClientIpWhitelistVerifyAdapter'
])

/**
 * 该 impl 是否可用于指定角色。
 * @param {string} impl 适配器实现类（如 `HmacCallbackVerifyAdapter`）
 * @param {'OUTBOUND'|'CALLBACK'|'CLIENT_AUTH'} role 角色（应用弹窗两下拉 / 接口「入站鉴权方式」）
 */
export function adapterMatchesRole(impl, role) {
  if (!impl) return false
  // 入站鉴权（v1.2）：只认 4 个已知实现（未知 impl 在闸门里必然 40108）
  if (role === 'CLIENT_AUTH') return CLIENT_AUTH_ONLY.has(impl)
  if (CLIENT_AUTH_ONLY.has(impl)) return role === 'CALLBACK'   // 可当回调验签，但不能当出站签名
  if (CALLBACK_ONLY.has(impl)) return role === 'CALLBACK'
  if (OUTBOUND_ONLY.has(impl)) return role === 'OUTBOUND'
  return true
}

/** 给下拉标签用的短提示（让"这个适配器该放哪一侧"在界面上可见） */
export function adapterRoleHint(impl) {
  if (CLIENT_AUTH_ONLY.has(impl)) return '入站鉴权 / 回调验签'
  if (CALLBACK_ONLY.has(impl)) return '仅回调验签'
  if (OUTBOUND_ONLY.has(impl)) return '仅出站签名'
  return '两者皆可'
}
