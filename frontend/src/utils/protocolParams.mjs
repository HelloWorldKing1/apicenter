/**
 * 协议参数（interface.protocol_params）前端工具 —— 《XML声明配置设计方案.md》v4.4 · B1 + B2。
 *
 * 纯函数（无 Vue / DOM 依赖），便于 Node 单测。
 *
 * 与后端的口径约定（很重要，别改）：
 *  1. **只输出非空键**：后端把「键存在但为空白」判为 40001（XmlProtoConfig.textStrict），
 *     前端不能用 `{root: ''}` 表达“用默认”——想用默认就**别输出这个键**；
 *  2. 全部为默认值（POX / 1.0 / UTF-8 / 无 root / 无命名空间）时返回 **null**：不提交该字段，
 *     数据库保持 NULL ⇒ 与“从未配置”完全等价（零回归）；
 *  3. **`type` 是唯一真相**（POX / SOAP_1_1 / SOAP_1_2，缺省 POX）：只有非 POX 才输出 `type`；
 *     `soap` 段仅在 SOP 类型下、且**有非默认项**时才输出（后端允许 `type=SOAP_*` 不带 `soap`）；
 *  4. **POX 下绝不输出 `soap` 键**（后端 40001 互斥）。
 */
export const XML_VERSIONS = ['1.0', '1.1']

/** 字符集白名单（与后端一致；UTF-16/UTF-32 被后端显式拒绝，故不列出） */
export const XML_ENCODINGS = ['UTF-8', 'GBK', 'GB2312', 'GB18030', 'Big5', 'Shift_JIS', 'ISO-8859-1', 'US-ASCII']

export const DEFAULT_XML_VERSION = '1.0'
export const DEFAULT_XML_ENCODING = 'UTF-8'
export const DEFAULT_ENVELOPE_PREFIX = 'soap'

/**
 * XML 类型选项（**唯一真相**）：
 * - `POX`：普通 XML（自定义契约）——出站 = 文档根 + 字段；响应原样解包
 * - `SOAP_1_1` / `SOAP_1_2`：包裹 Envelope/Body；响应解包 + Fault 分类
 *
 * 说明：Atom / RSS / QuakeML 等**文档格式不在下拉里**——它们是“上游返回什么文档”（我们只解不包），
 * 由 `POX` 覆盖；`rpc/encoded` 与 XML-RPC 为明确非目标。详见《B2完整SOAP开发计划.md》§2.5。
 */
export const XML_TYPES = [
  { value: 'POX', label: '普通 XML（POX）' },
  { value: 'SOAP_1_1', label: 'SOAP 1.1' },
  { value: 'SOAP_1_2', label: 'SOAP 1.2' },
]

/** 是否 SOAP 类型 */
export function isSoapType(type) {
  return type === 'SOAP_1_1' || type === 'SOAP_1_2'
}

/** 简化 NCName 校验（与后端 XmlProtoConfig.NCNAME 一致：禁冒号） */
export function isValidXmlName(name) {
  return typeof name === 'string' && /^[A-Za-z_][A-Za-z0-9._-]*$/.test(name)
}

/**
 * 表单字段 → protocol_params JSON 字符串。
 *
 * 注意：`prefix` **从属于** `uri` —— 只给 prefix 不给 uri 时**整个 `namespace` 块不产出**
 * （没有 URI 的前缀无意义；后端对 `{namespace:{prefix}}` 也会以「缺 uri」回 40001）。
 * 为防止这条规则**静默丢掉用户输入**，界面侧「命名空间前缀」输入框在 URI 为空时**禁用**，
 * 并在清空 URI 时**联动清空前缀**（可见的显式行为）。
 *
 * @returns {string|null} null = 不提交（用平台内置默认）
 */
export function buildProtocolParams({
  type, version, encoding, root, nsPrefix, nsUri,
  action, envelopePrefix, unwrapResponse,
} = {}) {
  const xml = {}
  const t = type || 'POX'
  if (t !== 'POX') {
    xml.type = t                       // 缺省 POX 时不输出 ⇒ B1 历史数据零迁移
  }
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
  if (isSoapType(t)) {
    const soap = {}
    const act = (action || '').trim()
    if (act) {
      soap.action = act
    }
    const env = (envelopePrefix || '').trim()
    if (env && env !== DEFAULT_ENVELOPE_PREFIX) {
      soap.envelopePrefix = env
    }
    if (unwrapResponse === false) {
      soap.unwrapResponse = false       // 仅非默认（关）才输出
    }
    if (Object.keys(soap).length > 0) {
      xml.soap = soap                   // 全默认时整个 soap 段省略（后端按默认处理）
    }
  }
  if (Object.keys(xml).length === 0) {
    return null
  }
  return JSON.stringify({ xml })
}

/**
 * protocol_params JSON 字符串 → 表单字段（解析失败/空 → 全默认）。
 * 兼容 B1：**没有 `type` 的历史数据视为 POX**。
 * @returns {{type:string, version:string, encoding:string, root:string, nsPrefix:string,
 *            nsUri:string, action:string, envelopePrefix:string, unwrapResponse:boolean}}
 */
export function parseProtocolParams(json) {
  const out = {
    type: 'POX',
    version: DEFAULT_XML_VERSION,
    encoding: DEFAULT_XML_ENCODING,
    root: '',
    nsPrefix: '',
    nsUri: '',
    action: '',
    envelopePrefix: DEFAULT_ENVELOPE_PREFIX,
    unwrapResponse: true,
  }
  if (!json || typeof json !== 'string' || !json.trim()) {
    return out
  }
  try {
    const xml = JSON.parse(json)?.xml
    if (!xml || typeof xml !== 'object') {
      return out
    }
    if (typeof xml.type === 'string' && xml.type) {
      out.type = xml.type
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
    const soap = xml.soap
    if (soap && typeof soap === 'object') {
      out.action = typeof soap.action === 'string' ? soap.action : ''
      out.envelopePrefix = typeof soap.envelopePrefix === 'string' && soap.envelopePrefix
        ? soap.envelopePrefix : DEFAULT_ENVELOPE_PREFIX
      out.unwrapResponse = soap.unwrapResponse !== false
    }
    return out
  } catch (e) {
    return out // 存储值非法（正常不会发生：后端保存期已校验）→ 回落默认，不阻塞表单
  }
}

/**
 * 是否需要「协议参数」区（以及是否提交 `protocolParams`）：**只看 `protocol_out`**。
 *
 * <p>为何不看 `protocol_in`：`protocol_params` 只作用于【出站报文构造】与【供应商响应解包】，
 * 二者都按 `protocol_out` 走；入站请求方向**不解包**（设计方案 Q17），所以 `in=XML / out=JSON`
 * 时这些参数永不生效。
 *
 * <p>⚠️ 早期实现按“任一侧为 XML”判定，导致 `OUTBOUND + in=XML / out=JSON` 时
 * **界面能配、能存，但永不生效**（典型的“配了不生效”陷阱）——已修正为只看 `protocol_out`。
 */
export function needsProtocolParams(protocolOut) {
  return protocolOut === 'XML'
}

/**
 * 提交体里的 `protocolParams` 取值（**唯一入口，不要在组件里直接调 buildProtocolParams**）：
 * 非 XML 出站 → `null`（不提交），这样即使用户先配了 XML 参数再把协议改回去，
 * **隐藏块的残留值也不会被下发**（否则后端会回 40001「配错了方向」）。
 */
export function protocolParamsForPayload(protocolOut, fields = {}) {
  if (!needsProtocolParams(protocolOut)) {
    return null
  }
  return buildProtocolParams(fields)
}

/** 根元素字段的标签（随类型切换，彻底消掉“一个字段两义”的歧义） */
export function rootFieldLabel(type) {
  return isSoapType(type) ? 'Body 内业务元素' : '根元素'
}
