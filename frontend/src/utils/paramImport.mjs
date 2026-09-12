/**
 * 请求参数快速导入内核（2026-09-12 评审定稿，D1–D6 按推荐实现）。
 *
 * 输入一段粘贴的 JSON（顺带支持 form-urlencoded），输出可直接写回 ParamTable 的参数行：
 *   { name, type, required, sample }
 *
 * 设计契约（沿用《接口监控设计方案》§8 的同一条原则）：
 * - **示例值取 token 原始切片**，不取 `JSON.parse` 的结构值 —— 否则 19 位数字会被改写
 *   （7494312521977267257 → …267000）、`1.10` → `1.1`、`1e3` → `1000`。
 *   `JSON.parse` 只用于**语法校验与错误定位**，不参与取值。
 * - 路径形态（D1）：嵌套用 `.`，数组元素用 `[0]`（取首个样本），与 ParamTable 点号路径惯用法一致；
 *   键名自身含 `.`/`[`/`]`/空格时用 `["原始键"]` 包裹，避免路径歧义。
 * - 必填策略（D2）：默认「全部必填」，仅 `null` 与空容器为否；可切「仅顶层必填」。
 * - 限额（D4）：条数 200、嵌套深度 6，超出截断并给 warning（不阻断导入）。
 */

import { isFormLike, tokenizeJson } from './payload.mjs'

/** 参数条数上限（超出截断） */
export const MAX_IMPORT_PARAMS = 200

/** 嵌套展开深度上限（超出只保留容器行） */
export const MAX_IMPORT_DEPTH = 6

/** 容器示例值压缩后的最大长度 */
const SAMPLE_MAX = 120

const TRUNCATION_SUFFIXES = ['...[truncated]', '…[truncated]']
const ESCAPE_KEY = /[.[\]\s]/

/**
 * 解析粘贴内容 → 参数行。
 * @param {string} text 粘贴的 JSON / form-urlencoded 文本
 * @param {{allRequired?:boolean, expand?:boolean, arraySample?:boolean,
 *          maxParams?:number, maxDepth?:number}} [options]
 * @returns {{ok:boolean, error:null|{message:string,line:number,column:number,snippet:string},
 *            truncated:boolean, format:'json'|'form', params:Array<{name,type,required,sample}>,
 *            warnings:string[], stats:{count:number,skipped:number,maxDepth:number}}}
 */
export function extractParams(text, options = {}) {
  const opts = {
    allRequired: options.allRequired !== false,   // 默认全部必填
    expand: options.expand !== false,             // 默认展开嵌套
    arraySample: options.arraySample !== false,   // 默认取数组首个元素
    maxParams: options.maxParams || MAX_IMPORT_PARAMS,
    maxDepth: options.maxDepth || MAX_IMPORT_DEPTH
  }
  const source = text == null ? '' : String(text)
  const result = {
    ok: false, error: null, truncated: false, format: 'json',
    params: [], warnings: [], stats: { count: 0, skipped: 0, maxDepth: 0 }
  }
  if (!source.trim()) {
    result.error = { message: '内容为空，请粘贴 JSON', line: 1, column: 1, snippet: '' }
    return result
  }

  let body = source
  for (const suffix of TRUNCATION_SUFFIXES) {
    if (body.endsWith(suffix)) {
      body = body.slice(0, -suffix.length)
      result.truncated = true
      break
    }
  }
  const trimmed = body.trim()
  if (!trimmed) {
    result.error = { message: '内容为空，请粘贴 JSON', line: 1, column: 1, snippet: '' }
    return result
  }

  // form-urlencoded（D5：顺带支持；无 `{`/`[` 开头且形如 k=v）
  if (!/^[{\[]/.test(trimmed) && isFormLike(trimmed, true)) {
    result.format = 'form'
    result.ok = true
    result.params = fromForm(trimmed, opts)
    result.stats.count = result.params.length
    if (result.truncated) result.warnings.push('内容疑似被截断，已按可见部分解析')
    return result
  }

  // ---- 语法校验（JSON.parse 仅用于校验 / 定位） ----
  let parsed
  try {
    parsed = JSON.parse(trimmed)
  } catch (e) {
    result.error = locateJsonError(String(e.message || e), trimmed)
    return result
  }
  result.ok = true

  // ---- 结构提取（token 原始切片，保真） ----
  // tokenizeJson 会把连续标点合并成一个 token（如 `}]}`，为高亮渲染省 span）；
  // 结构遍历需要逐个括号，故先按字符拆开（拆开不影响拼接无损性）。
  const tokens = tokenizeJson(trimmed).flatMap((t) => (t.type === 'punct'
    ? Array.from(t.text, (ch) => ({ type: 'punct', text: ch }))
    : [t]))
  const ctx = {
    opts, tokens, params: [], warnings: [], skipped: 0, maxDepth: 0, indexByPath: new Map()
  }
  const root = firstMeaningful(tokens, 0)
  const rootToken = tokens[root]
  if (rootToken && rootToken.type === 'punct' && rootToken.text === '{') {
    walkObject(ctx, root, 1, '')
  } else if (rootToken && rootToken.type === 'punct' && rootToken.text === '[') {
    const end = matchingIndex(tokens, root)
    const first = nextMeaningful(tokens, root + 1)
    // 顶层数组：直接把首个元素当作样本展开（不再为根数组本身生成容器行）
    if (first < end) walkValue(ctx, first, '[0]', 1)
  } else {
    result.ok = false
    result.error = {
      message: '顶层不是对象或数组，无法推断参数（可改用 form-urlencoded 或直接手填）',
      line: 1, column: 1, snippet: trimmed.slice(0, 60)
    }
    return result
  }

  result.params = ctx.params
  result.warnings = ctx.warnings
  result.stats = { count: ctx.params.length, skipped: ctx.skipped, maxDepth: ctx.maxDepth }
  if (result.truncated) result.warnings.push('内容疑似被截断，已按可见部分解析')
  return result
}

// ---------- JSON 结构遍历（tokens → 参数行） ----------

function walkObject(ctx, start, depth, parentPath) {
  const end = matchingIndex(ctx.tokens, start)
  let i = nextMeaningful(ctx.tokens, start + 1)
  while (i < end) {
    const keyToken = ctx.tokens[i]
    if (!keyToken || keyToken.type !== 'key') { i++; continue }
    const rawKey = keyToken.text
    let afterKey = nextMeaningful(ctx.tokens, i + 1)
    if (ctx.tokens[afterKey] && ctx.tokens[afterKey].text === ':') afterKey = nextMeaningful(ctx.tokens, afterKey + 1)

    let key
    try {
      key = JSON.parse(rawKey)   // 键名是字符串，无精度问题；同时正确还原转义
    } catch (e) {
      key = rawKey.slice(1, -1)
    }
    if (key === '') {
      warnOnce(ctx, '存在空键名，已跳过该分支')
      ctx.skipped++
      afterKey = walkValue(ctx, afterKey, null, depth)   // 跳过但必须推进下标
    } else {
      const path = key.includes('.') || key.includes('[') || key.includes(']') || /\s/.test(key)
        ? `${parentPath ? parentPath + '.' : ''}["${key}"]`
        : `${parentPath ? parentPath + '.' : ''}${key}`
      if (path.includes('["')) warnOnce(ctx, '键名含点号/方括号/空格，已用 ["键"] 包裹以免路径歧义')
      afterKey = walkValue(ctx, afterKey, path, depth)
    }
    i = nextMeaningful(ctx.tokens, afterKey)
    if (ctx.tokens[i] && ctx.tokens[i].text === ',') i = nextMeaningful(ctx.tokens, i + 1)
  }
}

/** 解析一个值；返回值为「值之后的下一个 token 下标」。path=null 表示只推进下标不记录（空键名分支） */
function walkValue(ctx, index, path, depth) {
  const i = nextMeaningful(ctx.tokens, index)
  const token = ctx.tokens[i]
  if (!token) return i
  const record = path != null
  const requiredHere = () => ctx.opts.allRequired || depth <= 1

  if (token.type === 'punct' && (token.text === '{' || token.text === '[')) {
    const isObject = token.text === '{'
    const end = matchingIndex(ctx.tokens, i)
    const empty = containerEmpty(ctx.tokens, i, end)
    ctx.maxDepth = Math.max(ctx.maxDepth, depth)
    if (isObject) {
      if (record) {
        pushParam(ctx, {
          name: path,
          type: 'object',
          required: !empty && requiredHere(),
          sample: empty ? '{}' : squeeze(ctx.tokens.slice(i, end + 1))
        })
      }
      if (record && !empty) {   // 空键名分支（record=false）整棵子树都不记录
        if (!ctx.opts.expand || depth >= ctx.opts.maxDepth) {
          if (ctx.opts.expand && depth >= ctx.opts.maxDepth) {
            warnOnce(ctx, `嵌套超过 ${ctx.opts.maxDepth} 层，更深层级已折叠为容器行`)
          }
        } else {
          walkObject(ctx, i, depth + 1, path)
        }
      }
    } else {
      if (record) {
        pushParam(ctx, {
          name: path,
          type: 'array',
          required: !empty && requiredHere(),
          sample: empty ? '[]' : `[${countArrayItems(ctx.tokens, i, end)} 项]`
        })
      }
      if (record && !empty && ctx.opts.expand && ctx.opts.arraySample && depth < ctx.opts.maxDepth) {
        const first = nextMeaningful(ctx.tokens, i + 1)
        if (first < end) walkValue(ctx, first, `${path}[0]`, depth + 1)
      }
    }
    return end + 1
  }

  // 标量：raw 切片直接作为示例值（保真）
  ctx.maxDepth = Math.max(ctx.maxDepth, depth)
  let type = 'string'
  let sample = token.text
  let required = true
  if (token.type === 'number') type = 'number'
  else if (token.type === 'literal') {
    if (token.text === 'true' || token.text === 'false') type = 'boolean'
    else { type = 'string'; sample = ''; required = false }   // null → 保守按可空字符串
  } else if (token.type === 'string') {
    type = 'string'
    sample = token.text
  }
  if (record) pushParam(ctx, { name: path, type, required: required && requiredHere(), sample })
  return i + 1
}

function pushParam(ctx, param) {
  const existing = ctx.indexByPath.get(param.name)
  if (existing != null) {
    ctx.params[existing] = param                     // D3：同名后者生效（与 JSON 语义一致）
    warnOnce(ctx, '存在重复键，已按「后者生效」保留')
    return
  }
  if (ctx.params.length >= ctx.opts.maxParams) {
    ctx.skipped++
    warnOnce(ctx, `参数条数超过上限 ${ctx.opts.maxParams}，超出部分已忽略`)
    return
  }
  ctx.indexByPath.set(param.name, ctx.params.length)
  ctx.params.push(param)
}

// ---------- form-urlencoded ----------

function fromForm(text, opts) {
  const params = []
  const seen = new Map()
  text.split('&').filter(Boolean).slice(0, opts.maxParams).forEach((pair) => {
    const eq = pair.indexOf('=')
    const rawKey = eq === -1 ? pair : pair.slice(0, eq)
    const rawValue = eq === -1 ? '' : pair.slice(eq + 1)
    const name = safeDecode(rawKey)
    const value = safeDecode(rawValue)
    const param = { name, type: guessScalarType(value), required: true, sample: value }
    if (seen.has(name)) params[seen.get(name)] = param
    else { seen.set(name, params.length); params.push(param) }
  })
  return params
}

function guessScalarType(value) {
  if (/^-?\d+(\.\d+)?([eE][+-]?\d+)?$/.test(value)) return 'number'
  if (value === 'true' || value === 'false') return 'boolean'
  return 'string'
}

function safeDecode(s) {
  try {
    return decodeURIComponent(s.replace(/\+/g, '%20'))
  } catch (e) {
    return s
  }
}

// ---------- token 辅助 ----------

function firstMeaningful(tokens, from) {
  return nextMeaningful(tokens, from)
}

function nextMeaningful(tokens, from) {
  let i = from
  while (i < tokens.length && tokens[i].type === 'plain') i++
  return i
}

/** `{`/`[` 的配对下标（找不到 = 内容被截断，返回最后一个 token 下标） */
function matchingIndex(tokens, start) {
  const open = tokens[start].text
  const close = open === '{' ? '}' : ']'
  let depth = 0
  for (let i = start; i < tokens.length; i++) {
    const t = tokens[i]
    if (t.type !== 'punct') continue
    if (t.text === open) depth++
    else if (t.text === close) {
      depth--
      if (depth === 0) return i
    }
  }
  return tokens.length - 1
}

function containerEmpty(tokens, start, end) {
  for (let i = start + 1; i < end; i++) {
    if (tokens[i].type !== 'plain') return false
  }
  return true
}

/** 数组第一层元素个数（按 depth==1 的逗号计数） */
function countArrayItems(tokens, start, end) {
  let depth = 0
  let items = 0
  let sawValue = false
  for (let i = start + 1; i < end; i++) {
    const t = tokens[i]
    if (t.type !== 'punct') {
      if (t.type !== 'plain') sawValue = true
      continue
    }
    if (t.text === '{' || t.text === '[') depth++
    else if (t.text === '}' || t.text === ']') depth--
    else if (t.text === ',' && depth === 0) items++
  }
  return sawValue || items > 0 ? items + 1 : 0
}

/** 容器示例值：字符串外的连续空白折叠为单空格（token 无损），再截断长度 */
function squeeze(tokens) {
  const text = tokens.map((t) => (t.type === 'plain' ? ' ' : t.text)).join('').replace(/ {2,}/g, ' ').trim()
  return text.length > SAMPLE_MAX ? `${text.slice(0, SAMPLE_MAX)}…` : text
}

function warnOnce(ctx, message) {
  if (!ctx.warnings.includes(message)) ctx.warnings.push(message)
}

// ---------- 错误定位 ----------

/**
 * 语法错误定位：优先用「结构扫描」给出人类可读的位置与原因（V8 新版消息常只有上下文、没有 position），
 * 扫描无异常时再退回解析正则从 V8 消息里抠 position / line-column。
 */
export function locateJsonError(message, text) {
  const structural = scanStructureIssue(text)
  if (structural) return structural
  return locateError(message, text)
}

/**
 * 结构扫描（不解析、只逐字符跟踪字符串与括号栈），返回第一处结构性问题的位置：
 * 括号不匹配 / 字符串未闭合 / 内容未闭合（多为截断）/ 顶层结束后有多余内容。
 */
export function scanStructureIssue(text) {
  const stack = []
  let inString = false
  let escape = false
  let line = 1
  let column = 0
  let lastLineStart = 0
  const posOf = (index) => {
    const before = text.slice(0, index)
    const ln = before.split('\n').length
    return { line: ln, column: index - before.lastIndexOf('\n') }
  }
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (ch === '\n') { line++; lastLineStart = i + 1 }
    column = i - lastLineStart + 1
    if (inString) {
      if (escape) escape = false
      else if (ch === '\\') escape = true
      else if (ch === '"') inString = false
      continue
    }
    if (ch === '"') { inString = true; continue }
    if (ch === '{' || ch === '[') { stack.push(ch); continue }
    if (ch === '}' || ch === ']') {
      const top = stack.pop()
      const expect = top === '{' ? '}' : top === '[' ? ']' : null
      if (!expect) {
        return issue('多余的闭合括号 ' + ch + '（前面没有对应的 ' + (ch === '}' ? '{' : '[') + '）', text, i)
      }
      if (expect !== ch) {
        return issue(`括号不匹配：期望 ${expect} 但遇到 ${ch}`, text, i)
      }
      if (stack.length === 0) {
        const rest = text.slice(i + 1)
        if (rest.trim() !== '') {
          const offset = i + 1 + (rest.length - rest.trimStart().length)
          return issue('顶层结构已结束但后面还有多余内容', text, offset)
        }
      }
    }
  }
  if (inString) return issue('字符串未闭合（内容可能被截断）', text, text.length - 1)
  if (stack.length > 0) {
    const unclosed = stack.map((c) => (c === '{' ? '}' : ']')).reverse().join('')
    return issue(`内容未闭合：还缺 ${stack.length} 个 ${unclosed}（内容可能被截断）`, text, text.length - 1)
  }
  return null

  function issue(msg, src, index) {
    const pos = posOf(Math.max(0, Math.min(index, src.length - 1)))
    const snippet = (src.split('\n')[pos.line - 1] || '').slice(0, 160)
    return { message: msg, line: pos.line, column: pos.column, snippet }
  }
}

/** 把 JSON.parse 的报错转成 行/列/上下文片段（V8 消息含 position 或 line/column） */
export function locateError(message, text) {
  const lineColumn = message.match(/line (\d+) column (\d+)/i)
  const position = message.match(/position (\d+)/i)
  let line = 1
  let column = 1
  if (lineColumn) {
    line = Number(lineColumn[1])
    column = Number(lineColumn[2])
  } else if (position) {
    const pos = Math.min(Number(position[1]), text.length)
    const before = text.slice(0, pos)
    line = before.split('\n').length
    column = pos - before.lastIndexOf('\n')
  }
  const snippet = (text.split('\n')[line - 1] || '').slice(0, 160)
  return { message: message.replace(/^JSON\.parse:\s*/, ''), line, column, snippet }
}
