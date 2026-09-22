/**
 * 管理面「测试接口」/「模拟回调」的请求体 → HTTP 载荷（2026-09-22 修正）。
 *
 * <p>**为什么需要它**：报文格式由**接口的入站协议**决定，不由调试工具决定。
 * 后端 `POST /interfaces/{id}/test`（与 `/test-callback`）收的是 **`byte[]` 原样字节**，
 * 随后按 `interface.protocol_in` 解码 ⇒ 前端必须**按入站协议**准备报文：
 *
 * <ul>
 *   <li>`protocol_in = XML` → **原样**发送文本（`Content-Type: application/xml`）</li>
 *   <li>`protocol_in = JSON`（默认）→ 解析成对象，交给 axios 序列化为 `application/json`</li>
 * </ul>
 *
 * <p>⚠️ **修正的缺陷**：早期两个入口都**无条件** `JSON.parse(body)` ⇒
 * **入站 XML 的接口根本无法用这两个工具调试**（填进 XML 立刻抛 V8 原文
 * `Unexpected token '<', "<request><"... is not valid JSON`，且没有任何方向性提示）。
 * 而报文预填（`row.bodies` 的 IN 样例、回调报文样例）在 XML 接口下恰好就是 XML ⇒ 一点就报错。
 */

/**
 * @param {string} protocolIn 接口的入站协议（`'JSON'` / `'XML'`；缺省按 JSON）
 * @param {string} text 界面上填写的请求体（预填或手改）
 * @returns {{data: (object|string), contentType: string}} 直接交给 `http.post(url, data, { headers: { 'Content-Type': contentType } })`
 * @throws {Error} 入站 JSON 但报文不是合法 JSON 时，抛出**可读**提示（不再是 V8 原文）
 */
export function buildTestPayload(protocolIn, text) {
  const raw = typeof text === 'string' ? text : ''
  if (protocolIn === 'XML') {
    return { data: raw, contentType: 'application/xml' }
  }
  if (!raw.trim()) {
    // 空报文 → 空对象（后端 body 为 optional，可接受）
    return { data: {}, contentType: 'application/json' }
  }
  try {
    return { data: JSON.parse(raw), contentType: 'application/json' }
  } catch {
    throw new Error(
      '请求体不是合法 JSON（该接口「入站协议」是 JSON）。'
      + '报文格式由「入站协议」决定：要用 XML 报文，请先把「入站协议」改成 XML。',
    )
  }
}
