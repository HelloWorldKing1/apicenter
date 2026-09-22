/**
 * 「测试接口 / 模拟回调」弹窗里**请求体输入框**的格式判定与结构美化（2026-09-22）。
 *
 * <p>核心口径：**报文格式由接口的「入站协议」决定**（`protocol_in`），不由这个输入框决定
 * —— 后端 `/test` 与 `/test-callback` 都收 `byte[]` 原样字节，再按 `protocol_in` 解码。
 * 所以这里的职责只有两件：① 按入站协议**提示**该填什么；② 按入站协议**美化结构**。
 *
 * <p>美化一律**无损**：复用 `utils/payload.mjs` 的扫描式缩进（**只增删空白**），
 * 不做 `JSON.parse` 重建 —— 19 位数字尾数 / `1.10` / 重复键都不能被改写。
 * **美化失败绝不吞掉用户输入**：返回 `ok:false` + 原文，由界面提示原因。
 */
import { formatJson, formatXml, isJsonLike, isXmlLike } from './payload.mjs'

/** 入站协议 → 报文格式（缺省按 JSON，与后端默认一致） */
export function bodyFormatOf(protocolIn) {
  return protocolIn === 'XML' ? 'XML' : 'JSON'
}

/** 输入框的**初始骨架**（按入站协议给一个能直接改的样例，避免给 XML 接口预填 `{}`） */
export function bodySkeleton(protocolIn) {
  return bodyFormatOf(protocolIn) === 'XML' ? '<request></request>' : '{}'
}

/** 工具栏上的格式标签（如 `XML · 按入站协议`） */
export function bodyFormatLabel(protocolIn) {
  return `${bodyFormatOf(protocolIn)} · 按「入站协议」`
}

/**
 * 输入框下方的提示语（**一短行**）：该填什么 + 填错会看到的报错码。
 * ⚠️ 保持短：太长会把工具栏挤乱（2026-09-22 实测）；示例放 `bodyPlaceholderFor`。
 */
export function bodyHintFor(protocolIn, { callback = false } = {}) {
  const what = callback ? '回调报文' : '请求体'
  if (bodyFormatOf(protocolIn) === 'XML') {
    return `入站协议为 XML ⇒ ${what}必须填 XML；填 JSON 会被后端拒（40002 报文格式非法）`
  }
  return `入站协议为 JSON ⇒ ${what}必须填合法 JSON；填 XML 会被前端拦下`
}

/** 输入框为空时的示例（placeholder）—— 具体例子放这里，不占提示行 */
export function bodyPlaceholderFor(protocolIn, { callback = false } = {}) {
  if (bodyFormatOf(protocolIn) === 'XML') {
    return callback
      ? '<request><event_id>evt-1</event_id></request>'
      : '<request><requestId>REQ-1</requestId></request>'
  }
  return callback ? '{"event_id":"evt-1"}' : '{"requestId":"REQ-1"}'
}

/**
 * 结构美化（**无损**）。
 * @returns {{ok: boolean, text: string, message: string}} `text` 为结果（失败时=原文）
 */
export function beautifyBody(text, protocolIn) {
  const raw = typeof text === 'string' ? text : ''
  if (!raw.trim()) {
    return { ok: true, text: raw, message: '' }
  }
  const fmt = bodyFormatOf(protocolIn)
  try {
    if (fmt === 'XML') {
      if (!isXmlLike(raw)) {
        return { ok: false, text: raw, message: '当前内容不像是 XML —— 已按原样保留（请核对报文，或改「入站协议」）' }
      }
      return { ok: true, text: formatXml(raw), message: '已按 XML 缩进美化（只增删空白，内容逐字节不变）' }
    }
    if (!isJsonLike(raw)) {
      return { ok: false, text: raw, message: '当前内容不是合法 JSON —— 已按原样保留（请修正后重试）' }
    }
    return { ok: true, text: formatJson(raw), message: '已按 JSON 缩进美化（只增删空白，内容逐字节不变）' }
  } catch (e) {
    return { ok: false, text: raw, message: `美化失败（已按原样保留）：${e?.message || e}` }
  }
}
