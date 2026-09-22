/**
 * 应用弹窗「内联凭证卡片」的提示语判定（2026-09-22 从 Apps.vue 抽出为纯函数，便于单测）。
 *
 * <p>五种情形，**按优先级**从上到下命中即返回：
 *
 * <ol>
 *   <li>非编辑态（新建应用还没保存）→ **无提示**（此时没有凭证可言）</li>
 *   <li>**已解绑但凭证仍在** → 说明「为什么还在 + 怎么清理」。
 *       背景（真实反馈 2026-09-22）：把「供应商签名适配器」清空后，出站签名凭证**不会**随之删除
 *       —— 这是**有意**的（凭证是独立资源、绑定只是引用；凭证明文不可回显，删了不可恢复，
 *       故不随解绑静默删除）。但此前该情形**没有任何提示**，用户会以为"删不掉/是 bug"。</li>
 *   <li>已绑定且凭证 `ACTIVE` → 无提示</li>
 *   <li>已绑定但**缺**凭证（`OUTBOUND`）→ 出站不带签名头，供应商 401</li>
 *   <li>已绑定但**缺**凭证（`CALLBACK`）→ 回调验签 40100</li>
 * </ol>
 *
 * <p>⚠️ 清理路径是**两段式**（后端守卫 `CredentialService.delete`：**仅 `RETIRED` 可物理删除**）：
 * 「应用详情 → 凭证区 → 先『**吊销**』→ 再『**删除**』」。
 */

/**
 * @param {object} p
 * @param {boolean} p.isEdit 是否编辑已有应用（新建态无凭证）
 * @param {boolean} p.credentialNeeded 该角色是否绑定了「需要凭证」的适配器
 * @param {boolean} p.hasCredential 该角色是否已有凭证行
 * @param {string} [p.status] 已有凭证的状态（ACTIVE / ROTATING / RETIRED）
 * @param {'OUTBOUND'|'CALLBACK'} p.kind 凭证角色
 * @param {boolean} [p.hasPublishedInbound] 该应用下是否有已发布入站接口（仅影响 CALLBACK 措辞）
 * @returns {string} 提示语；空串 = 不显示提示条
 */
export function credentialHint({
  isEdit, credentialNeeded, hasCredential, status, kind, hasPublishedInbound,
} = {}) {
  if (!isEdit) return ''

  // ② 已解绑但凭证仍在：解释 + 给出清理路径（否则用户会以为删不掉）
  if (!credentialNeeded) {
    if (!hasCredential) return ''
    return '该角色当前【未绑定适配器】：此凭证【已不再被使用】，系统有意保留（凭证明文不可回显，不随解绑自动删除以免误删）。'
      + '如需清理：应用详情 → 凭证区 → 先「吊销」再「删除」。'
  }

  // ③ 已绑定且已有 ACTIVE 凭证
  if (status === 'ACTIVE') return ''

  // ④/⑤ 已绑定但缺凭证
  if (kind === 'OUTBOUND') {
    return '未配置出站签名凭证：运行期请求将不带签名头，供应商侧会返回 401。'
  }
  return hasPublishedInbound
    ? '未配置回调验签凭证，且该应用下已有已发布入站接口：供应商回调将被 40100 拒绝。'
    : '未配置回调验签凭证：入站回调验签将直接 40100。'
}
