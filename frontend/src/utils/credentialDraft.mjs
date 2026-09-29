/**
 * 应用凭证卡片的**草稿 → 提交负载**组装（2026-09-24 从 `Apps.vue` 抽出，便于单测）。
 *
 * <p>背景（真实反馈）：云厂商签名适配器有 **3 个** secret 字段（`secretId` / `secretKey` / **`token`**），
 * 只填前两个时旧实现报「请填写完整的 SecretId / SecretKey / 临时凭证 Token」——因为它要求**要么全填、要么全空**，
 * **没有看字段的 `required` 标记**。而 `token` 是**可选**的（永久 AK/SK 直签就留空）。这里按 `required` 正确判定。
 *
 * ## 规则
 * | 场景 | 结果 |
 * |---|---|
 * | 所有字段都留空 | `{ ok: true, payload: null }` —— **不提交凭证**（凭证可事后补） |
 * | **必填**字段有空 | `{ ok: false, message }` —— 点名缺哪些（只报必填的，不报可选） |
 * | 有值但**可选**字段留空 | `{ ok: true, payload }` —— **可选字段整键省略**（不写空串、不写 null） |
 * | 单字段 | payload = 该字符串（兼容既有单密钥适配器） |
 * | 多字段 | payload = `JSON.stringify({...})`（平台按复合凭证整体加密） |
 */

/**
 * @param {Array<{key: string, label: string, required?: boolean}>} fields impl 元数据里的 secret 字段
 * @param {Object} draft 卡片里用户填的明文（key → 值）
 * @returns {{ok: true, payload: string|null} | {ok: false, message: string}}
 */
export function buildCredentialPayload(fields, draft) {
  const list = fields || []
  const value = (f) => String((draft || {})[f.key] ?? '').trim()
  const filled = list.filter((f) => value(f) !== '')
  if (filled.length === 0) {
    return { ok: true, payload: null }
  }
  // ⚠️ 未声明 required 时**按必填处理**（保守）：旧实现等价于"全字段必填"，这里不放松默认；
  //    要表达"可留空"必须在元数据里显式 required=false（如云厂商签名的 token）。
  const isRequired = (f) => f.required !== false
  const missingRequired = list.filter((f) => isRequired(f) && value(f) === '')
  if (missingRequired.length > 0) {
    return {
      ok: false,
      message: `请填写：${missingRequired.map((f) => f.label).join(' / ')}`
        + (list.some((f) => !isRequired(f))
          ? `（其余为可选：${list.filter((f) => !isRequired(f)).map((f) => f.label).join(' / ')}，可留空）`
          : '')
    }
  }
  const pairs = filled.map((f) => [f.key, value(f)])
  return { ok: true, payload: pairs.length === 1 ? pairs[0][1] : JSON.stringify(Object.fromEntries(pairs)) }
}
