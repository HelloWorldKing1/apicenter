/**
 * 报文展示层格式化（接口监控调用日志明细 / 状态机 Tab / 仪表盘最近日志共用）。
 *
 * 设计约束（2026-09-11 评审拍板）：
 * 1. **只增删空白，不改写任何 token** —— 因此**不用** JSON.parse + JSON.stringify：
 *    实测 `{"seller_id":7494312521977267257,"amount":1.10,"exp":1e3}` 经 parse+stringify 会变成
 *    `{"seller_id":7494312521977267000,"amount":1.1,"exp":1000}`（19 位 id 尾数丢失、1.10→1.1、1e3→1000、重复键丢前一个）；
 *    同理 XML 不用 DOMParser（会规范化 CDATA / 实体 / 属性引号 / 命名空间声明顺序）。
 *    本模块只做「字符串外的结构性空白规整」，token 逐字节保留。
 * 2. **对截断报文也要能用**：落库前 SensitiveDataMasker 会截断到 4096 字符并加 `...[truncated]`
 *    （状态机 Tab 的 preview 用另一种后缀 `…[truncated]`）——先剥离后缀，再尽力缩进（半截 JSON/XML 也可读）。
 * 3. 超大报文不美化（MAX_FORMAT_CHARS），避免主线程卡顿；调用方仍可看原文。
 * 4. 非 JSON/XML（纯文本、二进制、form-urlencoded 本期不美化）一律原文降级。
 */

/** 超过该字符数不做美化（仍可查看原文） */
export const MAX_FORMAT_CHARS = 256 * 1024

/** 两种截断后缀：SensitiveDataMasker（落库）/ MonitorService.preview（出站记录预览） */
const TRUNCATION_SUFFIXES = ['...[truncated]', '…[truncated]']

/**
 * 解析报文并给出展示所需的全部信息。
 * @param {string|null|undefined} text 原始报文（可能是 JSON / XML / 纯文本，可能带截断后缀）
 * @returns {{lang:'json'|'xml'|'text', raw:string, pretty:string, formatted:boolean,
 *            truncated:boolean, tooLarge:boolean, length:number, note:string}}
 */
export function analyzePayload(text) {
  const source = text == null ? '' : String(text)
  const blank = !source.trim()
  const raw = blank ? '' : source
  const result = {
    lang: 'text', raw, pretty: raw, formatted: false,
    truncated: false, tooLarge: false, length: raw.length, note: ''
  }
  if (blank) return result

  let body = raw
  for (const suffix of TRUNCATION_SUFFIXES) {
    if (body.endsWith(suffix)) {
      body = body.slice(0, -suffix.length)
      result.truncated = true
      result.note = '内容已截断（落库上限 4096 字符），缩进仅供参考'
      break
    }
  }
  const text0 = body.trim()
  if (!text0) return result

  if (text0.length > MAX_FORMAT_CHARS) {
    result.tooLarge = true
    result.note = '内容过大，未美化（可查看原文）'
    return result
  }

  if (isJsonLike(text0)) {
    result.lang = 'json'
    result.formatted = true
    result.pretty = formatJson(text0)
    return result
  }
  if (isXmlLike(text0)) {
    result.lang = 'xml'
    result.formatted = true
    result.pretty = formatXml(text0)
    return result
  }
  return result
}

/** JSON 探测：`{` / `[` 开头即视（不做完整解析——截断报文也走缩进） */
export function isJsonLike(text) {
  const c = text[0]
  return c === '{' || c === '['
}

/** XML 探测：`<tag` / `<?xml` / `<!--` / `<!DOCTYPE` */
export function isXmlLike(text) {
  return /^<([A-Za-z_]|\?[A-Za-z]|!--|!DOCTYPE)/.test(text)
}

// ---------- JSON：字符串感知扫描缩进 ----------

/**
 * JSON 缩进（2 空格）：逐字符扫描，维护 inString / escape / depth，
 * 只对字符串外的 `{ [ } ] , :` 做换行与缩进，其余 token（数字、字面量、字符串内容）原样输出。
 * @param {string} text 已 trim、无截断后缀的 JSON 文本
 */
export function formatJson(text) {
  const out = []
  let depth = 0
  let inString = false
  let escape = false
  const pad = () => '  '.repeat(Math.max(0, depth))
  const newline = () => { out.push('\n', pad()) }
  const lastMeaningful = () => {
    for (let k = out.length - 1; k >= 0; k--) {
      const c = out[k]
      if (c !== '' && !/[\s]/.test(c)) return c
    }
    return ''
  }
  const nextMeaningful = (from) => {
    for (let k = from; k < text.length; k++) {
      if (!/\s/.test(text[k])) return text[k]
    }
    return ''
  }

  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (inString) {
      out.push(c)
      if (escape) escape = false
      else if (c === '\\') escape = true
      else if (c === '"') inString = false
      continue
    }
    switch (c) {
      case '"':
        inString = true
        out.push(c)
        break
      case '{':
      case '[': {
        out.push(c)
        const next = nextMeaningful(i + 1)
        if (next !== '}' && next !== ']') {
          depth++
          newline()
        } else {
          depth++
        }
        break
      }
      case '}':
      case ']': {
        depth = Math.max(0, depth - 1)
        const prev = lastMeaningful()
        if (prev === '{' || prev === '[') {
          out.push(c)            // 空容器：{} / []
        } else {
          out.push('\n', pad(), c)
        }
        break
      }
      case ',':
        out.push(c)
        newline()
        break
      case ':':
        out.push(': ')
        break
      default:
        // 结构性空白丢弃（缩进由 newline 负责）；其余（数字 / true / false / null）原样
        if (!/\s/.test(c)) out.push(c)
    }
  }
  return out.join('')
}

// ---------- XML：标签感知扫描缩进 ----------

/**
 * XML 缩进（2 空格）：逐字符扫描并区分 标签 / 属性引号 / 注释 / CDATA / 处理指令 / DOCTYPE，
 * 只在标签边界换行；标签内字符（含属性顺序、引号形态）原样保留。
 * 文本节点：连续空白折叠为单空格（纯空白文本节点丢弃）；混合内容（文本后紧跟闭合标签）保持同行。
 * ⚠ 与 JSON 同理不做 DOM 解析——CDATA / 实体 / 命名空间声明顺序均不被改写；需逐字节核对时切「原文」。
 * @param {string} text 已 trim、无截断后缀的 XML 文本
 */
export function formatXml(text) {
  let out = ''
  let depth = 0
  let inlineDepth = 0   // 0 = 非行内模式；>0 = 混合内容文本 run 的起始深度（run 内标签不换行）
  const indent = (delta) => {
    depth = Math.max(0, depth + (delta || 0))
    if (out && !out.endsWith('\n')) out += '\n'
    out += '  '.repeat(depth)
  }
  const emitStandalone = (token) => {   // 注释 / PI / DOCTYPE / CDATA
    if (inlineDepth) {
      out += token
    } else {
      indent(0)
      out += token
    }
  }
  const emitText = (token) => {
    const collapsed = token.replace(/\s+/g, ' ')
    if (collapsed.trim() === '') return      // 纯空白文本节点丢弃，且不进入行内模式
    if (!inlineDepth) {
      inlineDepth = depth
      out += collapsed.replace(/^ /, '')
    } else {
      out += collapsed
    }
  }

  let i = 0
  const n = text.length
  while (i < n) {
    const lt = text.indexOf('<', i)
    if (lt === -1) { emitText(text.slice(i)); break }
    if (lt > i) emitText(text.slice(i, lt))

    if (text.startsWith('<!--', lt)) {                       // 注释
      const end = text.indexOf('-->', lt + 4)
      const stop = end === -1 ? n : end + 3
      emitStandalone(text.slice(lt, stop))
      i = stop
      continue
    }
    if (text.startsWith('<![CDATA[', lt)) {                  // CDATA：内容原样（含换行）
      const end = text.indexOf(']]>', lt + 9)
      const stop = end === -1 ? n : end + 3
      emitStandalone(text.slice(lt, stop))
      i = stop
      continue
    }
    if (text.startsWith('<!', lt)) {                         // DOCTYPE（可能含内部子集 []）
      let k = lt + 2, bracket = 0
      while (k < n) {
        const ch = text[k]
        if (ch === '[') bracket++
        else if (ch === ']') bracket--
        else if (ch === '>' && bracket <= 0) break
        k++
      }
      emitStandalone(text.slice(lt, Math.min(k + 1, n)))
      i = Math.min(k + 1, n)
      continue
    }
    if (text.startsWith('<?', lt)) {                         // 处理指令
      const end = text.indexOf('?>', lt + 2)
      const stop = end === -1 ? n : end + 2
      emitStandalone(text.slice(lt, stop))
      i = stop
      continue
    }
    // 普通标签：扫描到 '>'（跳过引号内的 '>'）
    let k = lt + 1
    let quote = ''
    while (k < n) {
      const ch = text[k]
      if (quote) { if (ch === quote) quote = '' }
      else if (ch === '"' || ch === "'") quote = ch
      else if (ch === '>') break
      k++
    }
    const stop = Math.min(k + 1, n)
    const tagText = text.slice(lt, stop)
    const isClose = tagText.startsWith('</')
    const selfClosing = /\/\s*>$/.test(tagText)

    if (isClose) {
      const before = depth
      depth = Math.max(0, depth - 1)
      if (inlineDepth && before >= inlineDepth) {
        out += tagText                    // 混合内容：文本 run 内的闭合标签不换行
      } else {
        indent(0)
        out += tagText
      }
      if (inlineDepth && depth < inlineDepth) inlineDepth = 0   // 文本 run 结束
    } else {
      if (inlineDepth) {
        out += tagText                    // 混合内容：行内标签（含自闭合）不换行
      } else {
        indent(0)
        out += tagText
      }
      if (!selfClosing) depth++           // 层级始终递增，否则行内 run 结束判定会提前触发
    }
    i = stop
  }
  return out.replace(/^\n+/, '')
}
