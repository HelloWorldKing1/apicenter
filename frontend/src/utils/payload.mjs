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
 * 3. 主线程只格式化到 MAX_FORMAT_CHARS；更大的报文交给 Web Worker（payloadFormat.worker.mjs，上限 WORKER_FORMAT_LIMIT），
 *    格式化期间先展示原文，完成后替换——保证大报文也能美化且不卡 UI。
 * 4. 支持 JSON / XML / form-urlencoded / HTTP 头串（`k: v | k2: v2`）四种美化；二进制（含 Base64 图片）只识别与提示，
 *    内容完整时给 Base64 内联预览；其余纯文本原样降级。
 * 5. 语法高亮走自研 tokenizer（tokenize* 三个函数），**只切分不改写**：把输入切成 {type,text} 序列，
 *    拼接后与输入逐字节相同（单测有「无损」断言）；组件用 v-for span 渲染，**禁止 v-html**。
 */

/** 主线程直接格式化上限（超过交给 Worker） */
export const MAX_FORMAT_CHARS = 256 * 1024

/** Worker 格式化上限（再大则不美化，仅原文） */
export const WORKER_FORMAT_LIMIT = 2 * 1024 * 1024

/** 语法高亮上限（超过只缩进不上色，避免 token 数组与 DOM 行数过大） */
export const HIGHLIGHT_MAX_CHARS = 64 * 1024

/** 默认折叠展示行数（超过时提供「展开全部」） */
export const FOLD_LINES = 60

/**
 * 「展开全部」的渲染行数硬上限（超过提示用复制看全文）。
 * 2026-09-12 下调 20000 → 2000：每行还会切成多个高亮 span，2 万行可产生十万级 DOM 节点导致卡顿。
 */
export const RENDER_MAX_LINES = 2000

/** 两种截断后缀：SensitiveDataMasker（落库）/ MonitorService.preview（出站记录预览） */
const TRUNCATION_SUFFIXES = ['...[truncated]', '…[truncated]']

/**
 * 解析报文并给出展示所需的全部信息。
 * @param {string|null|undefined} text 原始报文（JSON / XML / form / 纯文本 / 二进制，可能带截断后缀）
 * @param {string} [contentTypeHint] 来自请求头的 Content-Type（可选，用于加权探测）
 * @returns {{lang:'json'|'xml'|'form'|'binary'|'text', raw:string, pretty:string, formatted:boolean,
 *            truncated:boolean, tooLarge:boolean, length:number, note:string,
 *            contentType:string, binary:boolean, imagePreview:string|null}}
 */
export function analyzePayload(text, contentTypeHint) {
  const source = text == null ? '' : String(text)
  const blank = !source.trim()
  const raw = blank ? '' : source
  const hint = normalizeContentType(contentTypeHint)
  const result = {
    lang: 'text', raw, pretty: raw, formatted: false,
    truncated: false, tooLarge: false, length: raw.length, note: '',
    contentType: hint, binary: false, imagePreview: null
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

  // 二进制 / Base64 图片：识别与提示优先（避免把乱码铺满屏幕）
  const bin = detectBinary(text0, result.truncated)
  if (bin.binary) {
    result.lang = 'binary'
    result.binary = true
    result.imagePreview = bin.imagePreview
    result.note = bin.note
    return result
  }

  // Content-Type 提示优先（例：text/xml 但内容前有 BOM/空白，或 form-urlencoded 无 & 的单键值）
  if (hint.includes('json') && isJsonLike(text0)) {
    result.lang = 'json'
    result.formatted = true
    result.pretty = formatJson(text0)
    return result
  }
  if (hint.includes('xml') && isXmlLike(text0)) {
    result.lang = 'xml'
    result.formatted = true
    result.pretty = formatXml(text0)
    return result
  }
  if (hint.includes('x-www-form-urlencoded') && isFormLike(text0, true)) {
    result.lang = 'form'
    result.formatted = true
    result.pretty = formatForm(text0)
    return result
  }

  if (text0.length > MAX_FORMAT_CHARS) {
    result.tooLarge = true
    result.note = `内容过大（${fmtSize(text0.length)}），未在主线程美化`
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
  if (isFormLike(text0, false)) {
    result.lang = 'form'
    result.formatted = true
    result.pretty = formatForm(text0)
    return result
  }
  return result
}

/** 归一化 Content-Type：取 mime（去参数、转小写、去空白） */
export function normalizeContentType(contentType) {
  if (!contentType) return ''
  return String(contentType).split(';')[0].trim().toLowerCase()
}

/** 从脱敏后的请求头串（`k: v | k2: v2`）里取 Content-Type（取不到返回 ''） */
export function contentTypeOf(headersText) {
  if (!headersText) return ''
  const m = String(headersText).match(/(?:^|\|\s*)content-type\s*:\s*([^|]+)/i)
  return m ? normalizeContentType(m[1]) : ''
}

/** 字节数友好显示（用于「内容过大」提示） */
function fmtSize(chars) {
  return chars >= 1024 * 1024 ? `${(chars / 1024 / 1024).toFixed(1)}MB` : `${Math.round(chars / 1024)}KB`
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

/**
 * 展示取值：美化模式且有格式化结果 → pretty；否则 raw（供组件直接使用）。
 * 注意：调用方在 JS 里取 computed 必须 `.value`（模板才会自动解包）——
 * 2026-09-12 回归：组件内曾写 `view.formatted`（少 `.value`）→ 取到 undefined → 正文恒显空。
 * 把这条规则提成纯函数并单测，避免同类错误再次静默。
 * @param {{formatted:boolean, pretty:string, raw:string}} analyzed analyzePayload 结果
 * @param {'pretty'|'raw'} mode 展示模式
 */
export function pickPayloadText(analyzed, mode) {
  if (!analyzed) return ''
  return mode === 'pretty' && analyzed.formatted ? analyzed.pretty : analyzed.raw
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

// ---------- form-urlencoded ----------

/**
 * form 探测：`k=v&k2=v2` 形态。
 * @param {boolean} hinted Content-Type 已声明 form（放宽：允许单个 `k=v` 且无 `&`）
 */
export function isFormLike(text, hinted) {
  if (text.length > 64 * 1024) return false
  if (hinted) return /^[^\s=&]+=[^\s&]*(&[^\s=&]+=[^\s&]*)*$/.test(text)
  // 无提示时从严：至少两对，或一对 + 值含 URL 编码特征，避免把普通句子 `a=b` 误判
  return /^[^\s=&]+=[^\s&]*&[^\s=&]+=[^\s&]*$/.test(text)
}

/**
 * form-urlencoded 拆行（每个参数一行，值做 decodeURIComponent，失败保留原值）：
 * 只增删空白与做百分号解码的**展示**，不解码成对象、不重排、不去重。
 */
export function formatForm(text) {
  return text.split('&')
    .filter((pair) => pair !== '')
    .map((pair, i) => {
      const eq = pair.indexOf('=')
      const k = eq === -1 ? pair : pair.slice(0, eq)
      const v = eq === -1 ? '' : pair.slice(eq + 1)
      const decoded = tryDecode(v)
      const line = eq === -1 ? tryDecode(k) : `${tryDecode(k)} = ${decoded}`
      return i === 0 ? line : `  ${line}`   // 首行不缩进，其余对齐到 2 空格
    })
    .join('\n')
}

function tryDecode(s) {
  try {
    return decodeURIComponent(s.replace(/\+/g, '%20'))
  } catch (e) {
    return s
  }
}

// ---------- HTTP 头串（脱敏后 `k: v | k2: v2`） ----------

/**
 * 头串拆行：仅在 ` | ` **后面紧跟「头名: 」** 时才切分——
 * 头值本身含 ` | `（如正则、URL 参数）不会被误拆；单行头串原样返回。
 */
export function formatHeaders(text) {
  const parts = String(text).split(/\s\|\s(?=[!#$%&'*+\-.^_`|~0-9A-Za-z]+:)/)
  return parts.map((p) => p.trim()).filter(Boolean).join('\n')
}

/** 头串视图（与 analyzePayload 同构，便于组件统一处理） */
export function analyzeHeaders(text) {
  const source = text == null ? '' : String(text)
  const blank = !source.trim()
  const raw = blank ? '' : source
  const pretty = blank ? '' : formatHeaders(raw)
  const lines = pretty ? pretty.split('\n').length : 0
  return {
    lang: 'headers', raw, pretty, formatted: lines > 1, truncated: false,
    tooLarge: false, length: raw.length, note: '', contentType: '', binary: false, imagePreview: null
  }
}

// ---------- 二进制 / Base64 图片 ----------

const IMAGE_MAGICS = [
  { name: 'PNG', mime: 'image/png', bytes: [0x89, 0x50, 0x4e, 0x47] },
  { name: 'JPEG', mime: 'image/jpeg', bytes: [0xff, 0xd8, 0xff] },
  { name: 'GIF', mime: 'image/gif', bytes: [0x47, 0x49, 0x46, 0x38] },
  { name: 'WEBP', mime: 'image/webp', bytes: [0x52, 0x49, 0x46, 0x46] }
]

/**
 * 二进制探测：
 * - 含 NUL 或控制字符占比 > 5% → 二进制；
 * - 纯 Base64 且解出图片魔数 → 图片（**仅在内容未被截断时**给内联预览，截断的图片必然残缺）。
 * @returns {{binary:boolean, note:string, imagePreview:string|null}}
 */
export function detectBinary(text, truncated) {
  // eslint-disable-next-line no-control-regex -- 二进制探测就是要有意匹配控制字符
  const ctrl = (text.match(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g) || []).length
  // 真正的二进制：含 NUL 或控制字符占比 > 5%（Base64 文本本身不含控制字符，故另按魔数判定）
  const hardBinary = text.includes('\u0000') || ctrl / Math.max(1, text.length) > 0.05
  const base64ish = /^[A-Za-z0-9+/=\s]+$/.test(text) && text.length >= 64
  const bytes = base64ish ? decodeBase64Head(text) : null
  const magic = bytes ? IMAGE_MAGICS.find((m) => m.bytes.every((b, i) => bytes[i] === b)) : null
  if (!hardBinary && !magic) {
    return { binary: false, note: '', imagePreview: null }   // 长纯文本 / 长 token 不误判
  }
  const size = base64ish ? `≈${Math.max(1, Math.round(text.replace(/\s/g, '').length * 3 / 4 / 1024))}KB` : `${text.length} 字符`
  if (magic) {
    const couldPreview = !truncated
    return {
      binary: true,
      note: `二进制内容（${magic.name} 图片，Base64 编码 ${size}）` + (truncated ? '——内容已截断，不提供预览' : ''),
      imagePreview: couldPreview ? `data:${magic.mime};base64,${text.replace(/\s/g, '')}` : null
    }
  }
  return { binary: true, note: `二进制内容（${size}，非 JSON/XML/文本，已按原文保留）`, imagePreview: null }
}

function decodeBase64Head(text) {
  try {
    const clean = text.replace(/\s/g, '').slice(0, 512)
    if (typeof atob === 'function') {
      return Array.from(atob(clean).slice(0, 16), (c) => c.charCodeAt(0))
    }
    // 无 atob 的环境（少数 Node 运行时/构建期）：只解前 4 个字符判定魔数，避免引入 Buffer 依赖
    const table = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
    const bytes = []
    for (let i = 0; i + 1 < clean.length && bytes.length < 4; i += 2) {
      const a = table.indexOf(clean[i])
      const b = table.indexOf(clean[i + 1])
      if (a < 0 || b < 0) break
      bytes.push((a << 2) | (b >> 4))
    }
    return bytes.length ? bytes : null
  } catch (e) {
    return null
  }
}

// ---------- 语法高亮 tokenizer（只切分不改写，拼接后与输入逐字节相同） ----------

/** 是否值得高亮（超过上限只缩进不上色） */
export function highlightEnabled(text) {
  return typeof text === 'string' && text.length > 0 && text.length <= HIGHLIGHT_MAX_CHARS
}

/**
 * 按语言切 token：idempotent/lossless —— tokens.map(t => t.text).join('') === text。
 * @returns {Array<{type:string, text:string}>}
 */
export function tokenize(text, lang) {
  if (lang === 'json') return tokenizeJson(text)
  if (lang === 'xml') return tokenizeXml(text)
  return [{ type: 'plain', text }]
}

/** JSON 结构符号（避免转义密集的字符类，也便于阅读与 lint） */
const JSON_PUNCT = new Set(['{', '}', '[', ']', ':', ','])

/** JSON token：key / string / number / literal / punct / plain */
export function tokenizeJson(text) {
  const out = []
  const n = text.length
  const push = (type, s) => { if (s) out.push({ type, text: s }) }
  let i = 0
  while (i < n) {
    const c = text[i]
    if (/\s/.test(c)) {
      let j = i + 1
      while (j < n && /\s/.test(text[j])) j++
      push('plain', text.slice(i, j))
      i = j
      continue
    }
    if (c === '"') {
      let j = i + 1
      let esc = false
      while (j < n) {
        const ch = text[j]
        if (esc) esc = false
        else if (ch === '\\') esc = true
        else if (ch === '"') { j++; break }
        j++
      }
      let k = j
      while (k < n && /\s/.test(text[k])) k++
      push(text[k] === ':' ? 'key' : 'string', text.slice(i, j))
      i = j
      continue
    }
    if (c === '-' || (c >= '0' && c <= '9')) {
      let j = i + 1
      while (j < n && /[0-9eE+\-.]/.test(text[j])) j++
      push('number', text.slice(i, j))
      i = j
      continue
    }
    if (/[A-Za-z]/.test(c)) {
      let j = i
      while (j < n && /[A-Za-z]/.test(text[j])) j++
      push('literal', text.slice(i, j))
      i = j
      continue
    }
    let j = i + 1
    while (j < n && JSON_PUNCT.has(text[j])) j++
    push('punct', text.slice(i, j))
    i = j
  }
  return out
}

/** XML token：punct / tag / attr / attrvalue / comment / cdata / pi / doctype / text / plain */
export function tokenizeXml(text) {
  const out = []
  const n = text.length
  const push = (type, s) => { if (s) out.push({ type, text: s }) }
  let i = 0
  while (i < n) {
    if (text[i] !== '<') {
      const j = text.indexOf('<', i)
      const stop = j === -1 ? n : j
      push('text', text.slice(i, stop))
      i = stop
      continue
    }
    if (text.startsWith('<!--', i)) {
      const e = text.indexOf('-->', i + 4)
      const stop = e === -1 ? n : e + 3
      push('comment', text.slice(i, stop))
      i = stop
      continue
    }
    if (text.startsWith('<![CDATA[', i)) {
      const e = text.indexOf(']]>', i + 9)
      const stop = e === -1 ? n : e + 3
      push('cdata', text.slice(i, stop))
      i = stop
      continue
    }
    if (text.startsWith('<?', i)) {
      const e = text.indexOf('?>', i + 2)
      const stop = e === -1 ? n : e + 2
      push('pi', text.slice(i, stop))
      i = stop
      continue
    }
    if (text.startsWith('<!', i)) {
      let k = i + 2
      let bracket = 0
      while (k < n) {
        const ch = text[k]
        if (ch === '[') bracket++
        else if (ch === ']') bracket--
        else if (ch === '>' && bracket <= 0) break
        k++
      }
      const stop = Math.min(k + 1, n)
      push('doctype', text.slice(i, stop))
      i = stop
      continue
    }
    // 普通标签：扫到 '>'（跳过引号内的）
    let k = i + 1
    let quote = ''
    while (k < n) {
      const ch = text[k]
      if (quote) { if (ch === quote) quote = '' }
      else if (ch === '"' || ch === "'") quote = ch
      else if (ch === '>') { k++; break }
      k++
    }
    const tag = text.slice(i, k)
    const m = tag.length
    let p = 0
    let seenName = false
    let expectValue = false
    while (p < m) {
      const ch = tag[p]
      if (ch === '<') {
        const len = tag.startsWith('</', p) ? 2 : 1
        push('punct', tag.slice(p, p + len))
        p += len
        seenName = false
        expectValue = false
        continue
      }
      if (ch === '/' && tag[p + 1] === '>') { push('punct', '/>'); p += 2; continue }
      if (ch === '>') { push('punct', '>'); p++; continue }
      if (/\s/.test(ch)) {
        let q = p + 1
        while (q < m && /\s/.test(tag[q])) q++
        push('plain', tag.slice(p, q))
        p = q
        continue
      }
      if (ch === '=') { push('punct', '='); p++; expectValue = true; continue }
      if (ch === '"' || ch === "'") {   // 引号值：整体作为 attrvalue
        let r = p + 1
        while (r < m && tag[r] !== ch) r++
        push('attrvalue', tag.slice(p, Math.min(r + 1, m)))
        p = Math.min(r + 1, m)
        expectValue = false
        continue
      }
      let q = p
      while (q < m && !/[\s=/>]/.test(tag[q])) q++
      const name = tag.slice(p, q)
      push(expectValue ? 'attrvalue' : (seenName ? 'attr' : 'tag'), name)
      expectValue = false
      if (!seenName) seenName = true
      p = q
    }
    i = k
  }
  return out
}

/**
 * token 序列 → 行（每行是 token 数组，不含换行符）。
 * 供组件渲染行号 / 折行 / 高亮；CDATA 等跨行 token 会被正确拆分。
 */
export function splitTokenLines(tokens) {
  const lines = [[]]
  for (const t of tokens || []) {
    const parts = String(t.text).split('\n')
    parts.forEach((part, idx) => {
      if (idx > 0) lines.push([])
      if (part !== '') lines[lines.length - 1].push({ type: t.type, text: part })
    })
  }
  return lines.length && lines[lines.length - 1].length === 0 && lines.length > 1 ? lines.slice(0, -1) : lines
}
