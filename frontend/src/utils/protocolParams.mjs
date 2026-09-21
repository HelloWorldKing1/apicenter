/**
 * 协议参数（interface.protocol_params）前端工具 —— 《XML声明配置设计方案.md》v4.2 · B1。
 *
 * 纯函数（无 Vue / DOM 依赖），便于 Node 单测。
 *
 * 与后端的口径约定（很重要，别改）：
 *  1. **只输出非空键**：后端把「键存在但为空白」判为 40001（见 XmlProtoConfig.textStrict），
 *     前端不能用 `{root: ''}` 表达“用默认”——想用默认就**别输出这个键**；
 *  2. 全部为默认值（1.0 / UTF-8 / 无 root / 无命名空间）时返回 **null**：不提交该字段，
 *     数据库保持 NULL ⇒ 与“从未配置”完全等价（零回归）。
 */
export const XML_VERSIONS = ['1.0', '1.1']

/** ASCII 兼容字符集白名单（与后端一致；UTF-16/UTF-32 被后端显式拒绝，故不列出） */
export const XML_ENCODINGS = ['UTF-8', 'GBK', 'GB2312', 'GB18030', 'Big5', 'Shift_JIS', 'ISO-8859-1', 'US-ASCII']

export const DEFAULT_XML_VERSION = '1.0'
export const DEFAULT_XML_ENCODING = 'UTF-8'

/** 简化 NCName 校验（与后端 XmlProtoConfig.NCNAME 一致：禁冒号） */
export function isValidXmlName(name) {
  return typeof name === 'string' && /^[A-Za-z_][A-Za-z0-9._-]*$/.test(name)
}

/**
 * 表单字段 → protocol_params JSON 字符串。
 * @returns {string|null} null = 不提交（用平台内置默认）
 */
export function buildProtocolParams({ version, encoding, root, nsPrefix, nsUri } = {}) {
  const xml = {}
  if (version && version !== DEFAULT_XML_VERSION) {
    xml.version = version
  }
  if (encoding && encoding !== DEFAULT_XML_ENCODING) {
    xml.encoding = encoding
  }
  const r = (root || '').trim()
  if (r) {
    xml.root = r
  }
  const uri = (nsUri || '').trim()
  if (uri) {
    const ns = { uri }
    const prefix = (nsPrefix || '').trim()
    if (prefix) {
      ns.prefix = prefix
    }
    xml.namespace = ns
  }
  if (Object.keys(xml).length === 0) {
    return null
  }
  return JSON.stringify({ xml })
}

/**
 * protocol_params JSON 字符串 → 表单字段（解析失败/空 → 全默认）。
 * @returns {{version:string, encoding:string, root:string, nsPrefix:string, nsUri:string}}
 */
export function parseProtocolParams(json) {
  const out = {
    version: DEFAULT_XML_VERSION,
    encoding: DEFAULT_XML_ENCODING,
    root: '',
    nsPrefix: '',
    nsUri: '',
  }
  if (!json || typeof json !== 'string' || !json.trim()) {
    return out
  }
  try {
    const xml = JSON.parse(json)?.xml
    if (!xml || typeof xml !== 'object') {
      return out
    }
    if (typeof xml.version === 'string' && xml.version) {
      out.version = xml.version
    }
    if (typeof xml.encoding === 'string' && xml.encoding) {
      out.encoding = xml.encoding
    }
    if (typeof xml.root === 'string') {
      out.root = xml.root
    }
    const ns = xml.namespace
    if (ns && typeof ns === 'object') {
      out.nsUri = typeof ns.uri === 'string' ? ns.uri : ''
      out.nsPrefix = typeof ns.prefix === 'string' ? ns.prefix : ''
    }
    return out
  } catch (e) {
    return out // 存储值非法（正常不会发生：后端保存期已校验）→ 回落默认，不阻塞表单
  }
}

/** 是否需要显示「协议参数」区：任一协议为 XML（JSON 接口配 xml 段会被后端拒绝） */
export function needsProtocolParams(protocolIn, protocolOut) {
  return protocolIn === 'XML' || protocolOut === 'XML'
}
