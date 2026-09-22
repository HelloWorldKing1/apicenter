# B2 完整 SOAP 开发计划

> **依据**：《XML声明配置设计方案.md》v4.3 §4/§5/§8/§10.1/§12（**Q8=完整 SOAP 已拍板 · Q17/Q18 已定 · Q19=B2 延后**）、
> §5.4 的四条实现约束、实测探针 S1–S4（真实 SOAP 服务）与 C1–C5（代码事实）。
> **状态**：**编码依据已就绪，但开工受 §1 门槛阻塞**（Q19：待真实 SOAP 供应商样本）。
> **出口标准**：1.1 / 1.2 双版本真实服务跑通 + Fault 分类正确（含"不重试 + 不误熔断"）+ 全量测试全绿 + 文档同步（§6）。

---

## 1. 范围与定位

- **入口**：**B1 出口**（`interface.protocol_params` + XML 声明/根元素/命名空间已落地，
  全量 325 测试全绿、真实 USGS XML 端到端实录——见设计方案 §14）。B2 在 B1 的同一列与同一解析器上**增量**扩展 `soap` 段。
- **总盘**：**6.4 人日**（设计方案 §9 批次 B2；本计划拆为 T1–T11，见 §3）。
- **一句话定位**：把出站 XML 从"能配声明/根元素/命名空间"推进到"**能接真实 SOAP 服务**"，
  并**顺带修掉两个既有病灶**——① 5xx 抛出时**丢弃响应体**（`faultstring` 全程丢失）
  ② `Client` 类 Fault（HTTP 500，确定性错误）**被短重试 + 无限补偿**。

### 1.1 ⚠️ 开工前置条件（**gate**，缺一不开工）

| # | 条件 | 现状 |
|---|---|---|
| **G1** | **真实 SOAP 供应商的 WSDL / 请求响应报文样例** | ✅ **已关闭（2026-09-21）**：两层都满足 —— ① **公开可调用服务** 6 个（WSDL + 成功/Fault 原始报文）→ `src/test/resources/soap-samples/`（含对设计的 4 条修正 **C-1~C-4**，见《soap-samples/README.md》）；② **企业级 Header 样本**（此前唯一缺口）→ `src/test/resources/soap-samples/header/`（Amadeus 公开客户端仓库：WSDL 真实声明 `soap:header` + 三种 Header 家族，手写合成夹具）。**结论：原“6/6 未声明 Header ⇒ 不做”的依据被推翻** → `D-SOAP-8` 改判**分两步**（§2.7） |
| G2 | B1 已上线且稳定 | ✅ 已落地（325 测试全绿） |
| G3 | 该供应商可联调（测试环境可达、可开关） | ❌ 待确认 |

> **G1 缺席时的替代方案（仅技术验证，不作为验收锚点）**：用公网 `dneonline.com`（已实测 S1/S3/S4，免密钥）
> 先把 T1–T8 做完并跑通；**但不宣称 B2 完成**——因为 `D-SOAP-8`（不写 Header）、`envelopePrefix`
> 默认值、`action` 是否需要等决策都是**无样本下的保守取舍**，极可能与真实供应商不符。

### 1.2 不做什么（范围纪律，越界即评审）

| 不做 | 理由 / 去向 |
|---|---|
| **SOAP `Header` 自定义内容** | **本期仍不做，但口径已拍板**（`D-SOAP-8` → §2.7）：静态/占位符 Header = **B2.1**；密码学 Header（`PasswordDigest`）= **B3**。样本已到位（`soap-samples/header/`），不再是“无样本无法决策” |
| **SOAP 1.2 的 HTTP 200 + Fault** | 罕见；补它要改 `ResponseJudger`（**被前置编排共用**）→ backlog §13-2 |
| **平台对外暴露 SOAP 端点**（服务端 SOAP） | `protocol_in` 是平台自家契约；Q17 已定**入站不解包** → backlog §13.1 #11 |
| **WSDL 2.0** | 样本 **0/6**（且业界几乎无人用）；只需能读 **WSDL 1.1** 的 `soap:` / `soap12:` binding |
| **`rpc/encoded` 绑定风格**（SOAP 编码风格） | 需 `Body` 带 `encodingStyle` + 参数带 `xsi:type`，与平台“元素/字段”模型不匹配；样本 **6/6 均为 `document/literal`** → 本条为**明确不支持**（非目标） |
| **XML-RPC** | 另一套协议（`<methodCall>` / `<methodResponse>`，无 Envelope）→ **完全不在范围** |
| **`ack` 的 SOAP 化** | ack 是平台回给回调方的回执，供应商回调普遍是普通 POST（D-SOAP-6） |
| **原生目标编码字节** | Woodstox 实测做不到（探针 #9/#10）→ backlog §13-4 |
| 自定义静态请求头（除 `SOAPAction`） | 独立课题（与"接口级静态头"一起做） |

---

## 2. 设计定稿（编码前必读）

### 2.1 线协议（1.1 / 1.2 对照，均经真实服务实测）

| 项 | SOAP 1.1 | SOAP 1.2 |
|---|---|---|
| Envelope 命名空间 | `http://schemas.xmlsoap.org/soap/envelope/` | `http://www.w3.org/2003/05/soap-envelope` |
| `Content-Type` | `text/xml; charset=<enc>` | `application/soap+xml; charset=<enc>; action="<action>"` |
| action 传递 | **独立头** `SOAPAction: "<action>"` | **在 Content-Type 内**（无 SOAPAction 头） |
| Fault 代码字段 | `faultcode` = `soap:Client` / `soap:Server` | `Code.Value` = `env:Sender` / `env:Receiver` |
| Fault 消息字段 | `faultstring` | `Reason.Text` |

产出样例（1.1，`root=Add`、业务命名空间 `http://tempuri.org/`）：

```xml
<?xml version='1.0' encoding='UTF-8'?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <Add xmlns="http://tempuri.org/"><intA>1</intA><intB>2</intB></Add>
  </soap:Body>
</soap:Envelope>
```

### 2.2 配置形态（B1 同一列；**增量加 `type` 与 `soap`**）

```jsonc
{ "xml": {
    "type":    "SOAP_1_1",                           // ← B2 新增，**唯一真相**：POX（默认）/ SOAP_1_1 / SOAP_1_2
    "version": "1.0", "encoding": "UTF-8",        // XML 声明版本，与 SOAP 版本**无关**
    "root":    "Add",                               // SOAP 下 = <Body> 内业务元素
    "namespace": { "prefix": "", "uri": "http://tempuri.org/" },
    "soap": {                                       // ← B2 新增；**仅 type=SOAP_* 允许**（POX 带它就是 40001）
      "action":  "http://tempuri.org/Add",          // 可选（实测 6/6 服务不强制）；1.1 → SOAPAction 头，1.2 → Content-Type 的 action=
      "envelopePrefix": "soap",                     // 默认 soap（1.2 常用 env）；前缀本身无语义
      "unwrapResponse": true                        // 响应解包，默认 true
    }
} }
```

> **`soap` 段不再带 `version`**（方案 A 的连带结论）：版本已由 `type` 携带，两处写同一件事会造成双真相。

> **B1 遗留的过渡**：B1 把 `soap` 段列为**未知键 → 40001**（提示"B2 延后"）。B2 开工第一件事就是
> 把它从 `KNOWN` 之外移入白名单（`XmlProtoConfig.KNOWN_XML_KEYS` + 新增 `KNOWN_SOAP_KEYS` + `KNOWN_XML_KEYS` 加 `type`），
> 并去掉那句提示；同时把“soap 段被拒”的单测**反向改写**（§7.3 衔接清单）。

### 2.3 Fault 分类（**B2 的核心价值**，实测 S3 + 样本集 C-2）

> **1.1 规范的 faultcode 是有限枚举**：`VersionMismatch` / `MustUnderstand` / `Client` / `Server`（1.2 对应 `Sender` / `Receiver`）
> ——分类表必须**穷举**，不能留“其他”分支（否则确定性错误会被当服务端故障无限补偿）。

| faultcode | 语义 | 终态 | 错误码 | 短重试 | 补偿 | 熔断计数 |
|---|---|---|---|---|---|---|
| `Client` / `Sender` | **我们请求错了**（参数/格式/权限） | **DEAD_LETTER** | **`50203`（新增）** | ❌ | ❌ | ❌ **不计失败** |
| **`VersionMismatch`** / **`MustUnderstand`** | **版本/命名空间不匹配、Header 无法理解**（**确定性配置错误**） | **DEAD_LETTER** | **`50203`** | ❌ | ❌ | ❌ **不计失败** |
| `Server` / `Receiver` | 供应商服务端故障 | COMPENSATING | `50201` | ✅ | ✅ | ✅ 计失败 |
| 非 Fault 的纯 5xx | 保持现状 | COMPENSATING | `50201` | ✅ | ✅ | ✅ 计失败 |

> **C-2 来源**：样本 `fault/hello-versionmismatch-11only.*`——向 **1.1-only** 服务发 1.2 报文，实测得到
> **HTTP 500** + `<faultcode>soap:VersionMismatch</faultcode>`（报文是 **1.1 结构**）。
> 它看上去像 5xx，但**重试/补偿毫无意义**（我们发错了版本）→ 必须归客户端类。

> **Fault 方言由 `type` 决定**：`type=SOAP_1_1` 时按 `faultcode`（`Client`/`Server`/`VersionMismatch`/`MustUnderstand`）解析；
> `type=SOAP_1_2` 时按 `Code/Value`（`Sender`/`Receiver`）解析并归入同一张表。注意：1.1-only 服务对 1.2 报文
> 会**用 1.1 结构回 Fault**（实测），所以解析要看**报文自身的 envelope 命名空间**，不能只看配置的 `type`。

### 2.4 ⚠️ 四条必须照抄的实现约束（设计方案 §5.4，不照抄必出错）

1. **`SoapClientFaultException` 必须直接继承 `RuntimeException`** —— Spring 7 `@Retryable.includes` 是
   **白名单**（默认空数组，已 `javap` 验证），若继承 `HttpServerErrorException` 会因 `instanceof` 命中白名单**继续重试**，
   "Client 类不重试"静默失效。
2. **`OutboundEngine` 的 catch 是「单条 `catch (Exception)` + 内部 instanceof 链」** —— 新分支必须放在**链最前面**，
   否则异常会走到 `throw e` 冒成 `BizException(50000)`。
3. **`PreStepExecutor` 必须显式加 `catch (SoapClientFaultException)`** —— 否则落进它的 `catch (Exception e)`，
   被归成 `PreStepFailure.Kind.CONFIG_ERROR` / **`40001`「调用异常」**（语义完全错）。
4. **`faultstring` 必须截断** —— 实测 S3 的 faultstring 是 ~1.5KB 的 .NET 全堆栈：
   `dead_letter.reason` 保留 `faultcode` + **前 500 字符** + `…[截断，原长 N]`；`payload` = **供应商原始响应体全量**（取证）；
   `alert_event.message` 只放首行。

### 2.5 XML 类型选择器（**已定稿：方案 A**，2026-09-21 拍板）

**位置**：接口弹窗 →「高级」→ 协议参数区的**首行**，**显示条件 = `protocol_out = XML`**
（与 `protocol_params` 的生效面一致；入站 XML / 出站 JSON 时整块不显示）。

**形态：一个“XML 类型”下拉（单选）**，而不是“SOAP 开关 + 版本”两个控件：
单选天然互斥（不会出现“开关关了但版本还留着”的组合态），且与 `protocol_in/out` 的“协议选择”心智一致。

| 下拉值 | UI 标签（**已拍板**） | 现阶段 | 选后行为 |
|---|---|---|---|
| `POX`（默认） | **普通 XML（POX）** | ✅ **可选** | = 现有 B1 行为：出站 `<root>…` + 字段；响应原样解包（不剥任何壳） |
| `SOAP_1_1` | **SOAP 1.1** | ⏸ **B2 后可选** | 包裹 `Envelope/Body`；`Content-Type: text/xml` + `SOAPAction` 头；响应解包 + Fault 分类 |
| `SOAP_1_2` | **SOAP 1.2** | ⏸ B2 后可选 | 包裹；`Content-Type: application/soap+xml; action=`（**无** `SOAPAction` 头）；Fault 用 `Code/Reason` |
| —— | Atom / RSS / QuakeML 等**文档格式** | ❌ **不列**（已接受） | 它们是**上游返回的文档格式**（我们只解不包），**不需要单独选项** → 由 `POX` 覆盖 |
| —— | SOAP `rpc/encoded`、**XML-RPC** | ❌ 不列（已接受） | 非目标（样本 6/6 均 `document/literal`；XML-RPC 另一套协议） |

> **为何不做 `auto`（自动探测）**：请求侧**必须显式选**——发错版本会得到 `VersionMismatch`（已实测）；
> 响应侧**不做探测**（D-SOAP-9）。hint 建议：“不确定时先用 **SOAP 1.1**（样本 6/6 都支持 1.1，仅 5/6 支持 1.2）”。

**唯一真相（**已拍板：方案 A**）**：

| 方案 | 规则 | 结论 |
|---|---|---|
| **A** ✅ **采用** | **`xml.type` 为权威**（POX / SOAP_1_1 / SOAP_1_2）；`soap` 段降为**该类型的配置载体**（仅 `SOAP_*` 下允许，且**不再含 version**） | 单一事实来源、UI 单选、**审计/日志/快照 diff 能直接读出“这个接口是什么类型”**；**B1 旧数据无 `type` → 视为 `POX`，零迁移** |
| B ❌ 被否 | 不加 `type`，仍靠“`soap` 段是否存在”推断（= 原 D-SOAP-1） | 改动小，但 UI 仍需“开关+版本”两控件，且诊断时要人脑从参数反推类型；快照 diff 不够直白 |

**落地要点**：
- 后端 `XmlProtoConfig`：`type` 缺省 = `POX`；**`type=POX` 但带 `soap` 键 → `40001`（互斥）**；
  `type=SOAP_*` 但 `soap` 段缺省 → 用默认值（`action` 可空、`envelopePrefix` 默认 `soap`、`unwrapResponse` 默认 true）；
- **UI 按类型切换子项**：选 `POX` → 只显示本期四个参数；选 `SOAP_*` → 额外显示 action / envelopePrefix / unwrapResponse，
  并把 `root` 标签从「根元素」改为「**Body 内业务元素**」（**彻底消掉双语义歧义**——原设计方案靠动态标签缓解，此处变为类型驱动）；
- 选择器**在 B2 之前不提前露出** `SOAP_*` 选项（不给出“选了必然 40001”的入口）。

#### 2.5.1 逐控件验收清单（UI 开工直接照此实现与验收）

**显隐矩阵**（列 = XML 类型；✅ 显示 / ❌ 不显示 / — 值不提交）

| 控件（顺序自上而下） | 控件类型 | 默认值 | POX | SOAP 1.1 | SOAP 1.2 |
|---|---|---|---|---|---|
| **XML 类型** | select（单选） | `普通 XML（POX）` | ✅ | ✅ | ✅ |
| XML 声明 version | select（1.0 / 1.1） | `1.0` | ✅ | ✅ | ✅ |
| XML 声明 encoding | select（ASCII 兼容白名单） | `UTF-8` | ✅ | ✅ | ✅ |
| 根元素 root | input（标签**随类型变**） | 空（= 平台默认 `request`） | ✅ 标签「根元素」 | ✅ 标签「**Body 内业务元素**」 | ✅ 同左 |
| 命名空间前缀 | input | 空（= 默认命名空间） | ✅ | ✅ | ✅ |
| 命名空间 URI | input | 空（= 不写命名空间） | ✅ | ✅ | ✅ |
| SOAPAction / action | input | 空（**可选**） | ❌ | ✅（提示：从 `Content-Type` 取） | ✅（提示：写进 `Content-Type`） |
| envelope 前缀 | input | `soap` | ❌ | ✅ | ✅ |
| 响应解包 Envelope | switch | 开 | ❌ | ✅ | ✅ |
| 灰色 hint | 静态文案 | —— | ✅（默认可不显） | ✅ | ✅ |

**逐控件验收判据**：

| # | 控件 | 验收判据（“怎么算做对”） |
|---|---|---|
| 1 | XML 类型 | 切 `POX` → SOAP 三个子项**立即隐藏**且**不提交**；切 `SOAP_*` → 三个子项出现；**切类型不丢已有值**（切换回去能恢复） |
| 2 | 声明 version / encoding | 只影响 `<?xml version=… encoding=…?>`；**encoding 的 hint 必须写明“只改声明、非 ASCII 转字符引用、产不出原生 GBK 字节”** |
| 3 | 根元素 root | label 随类型切换（「根元素」↔「Body 内业务元素」）；非法值（含 `:` / 空格 / `1abc`）**保存时被拒**（提示具体原因） |
| 4 | 命名空间前缀 / URI | 只填前缀不填 URI → **不提交 namespace**（不报错）；URI 非法 → 保存被拒；**前缀留空 = 默认命名空间**（提示要写清） |
| 5 | action | **可留空**（三种类型下均不得因空而报错）；填入后在 1.1 走 `SOAPAction` 头、在 1.2 走 `Content-Type` 的 `action=`——与 T2 的验收点对应 |
| 6 | envelope 前缀 | 非法前缀 → 保存被拒；默认 `soap` 时**不写进 JSON**（只下发非空且非默认的键） |
| 7 | 响应解包 switch | 关掉后，SOAP 响应**保留 `Envelope` 层级**（`data.Envelope.Body.…`）；开启则业务字段在 `data` 根 —— 与 T3 验收点对应 |
| 8 | 提交体形态 | `protocolParams` 必须形如 `{"xml":{"type":"SOAP_1_1",…}}`；**POX 下不得出现 `soap` 键**（否则后端 40001）；非 XML 出站 → 整个字段为 `null` |
| 9 | 回读一致性 | 保存 → 重新打开 → 控件值与提交一致；**只填过的项才回显**（未填项显示为空/默认，而不是被写成默认值） |
| 10 | 历史兼容（**关键**） | 打开一个 **B1 时期创建**的接口（其 `protocol_params` **没有 `type`**）→ 类型应显示为「普通 XML（POX）」而不是空/报错 |

> **验收方式**：①~⑩ 均为页面操作；对应后端行为（互斥 40001 / 只下发非空键 / POX 零迁移）已有单测覆盖（T1），
> UI 侧建议同步加 `utils/protocolParams` 单测（含“POX 不带 soap 键”“缺 type 视为 POX”）+ 1 例 SSR 冒烟。

---

### 2.6 开工前复核的 3 个技术事实（已实测，实现时对齐）

| # | 事实 | 证据 |
|---|---|---|
| 1 | `@Retryable.includes` 默认 `[]`（项目显式列 3 类 → 白名单收窄） | `javap` 注解默认值 |
| 2 | `HttpServerErrorException.create(status, text, headers, body, charset)` 可带 body | `javap` Spring 7.0.8 |
| 3 | 三参 `writeStartElement(prefix,local,ns)` + `writeNamespace` 才能正确输出 xmlns | Woodstox 探针 #2/#3 |

---

### 2.7 `D-SOAP-8` 拍板：Header **分两步**（**G1 已关闭**，2026-09-21）

> **依据**：`src/test/resources/soap-samples/header/`（手写合成夹具 + 观察点 O-1~O-5，见其 README）。

**样本偏差被实证推翻**：公开免密钥服务 **0/6** 声明 `soap:header`；企业级（Amadeus 公开客户端仓库）**1/1** 声明，
**且服务端在响应里也回传 Header**。⇒ 原“6/6 未声明 ⇒ 不做”的依据不成立，改判为下述两步（而非“一概不做”）。

| 步 | 范围 | 值从哪来 | 判定 |
|---|---|---|---|
| **第一步（= B2.1，紧随 SOAP 主体）** | **静态 / 占位符 Header**：可配置一棵 Header 元素树（元素名 + 命名空间/前缀 + 文本值 + **属性**），值可引用凭证或入参 | 静态 / 凭证 | ✅ 可覆盖 **WS-Addressing**（夹具①）与厂商 **`Session`/`AMA_*`** 类（夹具③-a/③-b） |
| **第二步（= B3）** | **密码学 Header 构造器**：`UsernameToken` + `PasswordDigest`（+ 可选 `Timestamp`/签名） | **每请求动态** | ❌ **静态配置不可能覆盖**：`PasswordDigest = Base64(SHA1(nonce ‖ Created ‖ password))`，`Nonce`/`Created` 都变 ⇒ 属独立能力 |

**本期（B2）编码范围不变**：T1–T9 **不含** Header，§1.2 非目标继续有效；本节只做「gate 关闭 + 口径拍板」，**不新增任务**。

**四条必须钉住的约束**（源自真实报文观察，B2.1/B3 落地前先读）：

1. **属性必须可表达**（O-4：厂商头的语义可能全在属性上，元素本体为空）；
2. **整体替换而非追加**（O-5：同一服务存在两代 Header 形态，供应商会演进）；
3. **B3 的重试必须复用同一 `Created`/`Nonce`** —— 否则 `@Retryable` 重试会被供应商判为**重放**（签名/时间戳场景的经典陷阱）；
4. **不支持的 Header 形态一律 `40001`**（不静默忽略，同 B1 纪律）—— B2.1 引入该配置项时适用。

---

## 3. 任务拆解（**6.4 人日**）与任务级验收点

| # | 任务 | 人日 | 工作项 | 验收点（完成的可验证标志） |
|---|---|---|---|---|
| **T1** | SOAP 配置解析与校验 | 0.3 | `XmlProtoConfig` 加 `type`（§2.5 方案 A：`POX`/`SOAP_1_1`/`SOAP_1_2`，缺省 `POX`）与 `soap` 段：**SOAP 版本由 `type` 携带（`soap` 段不含 `version`）**；`action` 可选；`envelopePrefix` 合法前缀；`unwrapResponse` 布尔；**`type=POX` 带 `soap` 键 → 40001（互斥）**；未知键仍 40001 | 单测：1.1/1.2 解析、**`action` 缺省可保存**、`type=POX`+`soap`→40001、`type` 缺省视为 POX（零迁移）、`soap` 内拼错键→40001、B1 的“soap 段拒绝”用例**改为正向** |
| **T2** | 请求包裹（Envelope/Body + 命名空间） | 0.8 | `XmlProtocolAdapter.encode` 在 `soap` 存在时先写 `Envelope`→`Body`，业务元素按 `root`/`namespace` 写在 Body 内；`Content-Type` 按 1.1/1.2 分派；1.1 加 `SOAPAction` 头 | 集成：1.1 报文含 Envelope/Body + `SOAPAction` 头；1.2 报文 env ns 为 2003/05 + `Content-Type` 带 `action=` 且**无** `SOAPAction` 头 |
| **T3** | 响应解包（**仅响应方向**，Q17） | 0.5 | `decode` 判据：根 localName==`Envelope` **且**有 `Body` → 取 Body 内第一个元素为业务根；否则原样 + `log.warn`；**入站请求方向不解包**（B1 已注 | 集成：供应商返回 Envelope → `data` **不含 Envelope 层级**（用例 8）；用例 12 回归（入站解码不受影响） |
| **T4** | `UpstreamInvoker` 5xx 带 body 抛 + Fault 探测 | 1.0 | `OutboundRequestSpec` 加 `soapVersion`（ENCODE 时置位）；5xx 分支改 `HttpServerErrorException.create(…, body …)`；**结构匹配** `Body/Fault`（非字符串嗅探）且 `soapVersion` 非空时：Client/Sender → `throw SoapClientFaultException`（**直接继承 RuntimeException**） | 单测/集成：5xx 的 faultstring 可见（用例 16）；**反证**：非 SOAP 5xx 行为不变（用例 15） |
| **T5** | `OutboundEngine` Fault 分类 | 0.8 | instanceof 链**最前**加分支 → 写 `dead_letter`（payload = 原始响应体；reason 截断）→ `DEAD_LETTER` + `50203`；**不进** `record(false)`（熔断不计失败） | 集成：Client Fault → 终态 `DEAD_LETTER`/`50203`、**WireMock 计数=1**（不重试）、**熔断仍 CLOSED**（用例 9）；Server Fault → `COMPENSATING`/`50201`（用例 10） |
| **T6** | `PreStepExecutor` 分支（易漏点） | 0.3 | 新增 `catch (SoapClientFaultException)` → `50203`（复用 `Kind.HTTP_5XX`，**不新增出口**） | 集成：前置目标返回 Client Fault → 分类为 SOAP 客户端错误 + `50203`，**不得**是 `40001 CONFIG_ERROR`（用例 23） |
| **T7** | 指标口径 | 0.2 | `CallLogAspect.outcomeOfOut` 把该异常归 **`upstream_fail`**（与 4xx 一致，**不是** `transport_fail`） | 集成：`apicenter.gateway.requests{outcome=upstream_fail}`（用例 25） |
| **T8** | 错误码登记 | 0.1 | 《API中心设计方案.md》§6.2 502xx 段加 `50203`（语义：SOAP Fault（客户端错误，不重试）） | 错误码表与 `BizException` 常量一致、与 50201/50202 语义不重叠 |
| **T9** | 前端 **XML 类型选择器** + SOAP 子表单 | 0.6 | 接口「高级 → 协议参数」首行加**“XML 类型”单选**（§2.5：`POX` / `SOAP_1_1` / `SOAP_1_2`）；按类型切换子项与 `root` 标签；`utils/protocolParams.mjs` 扩展（含单测） | 前端 `npm run lint` 0 error + `npm test` 全绿 + `npm run build` 通过；选 `SOAP_*` 后子项才出现；`POX` 下带 `soap` 段被 40001 |
| **T10** | 测试 | 1.5 | §4 全部用例（含 4 条反证/契约断言）；**SOAP 用例必须隔离 worker**（四属性置 1h） | 新增用例全绿；**全量**（325+新增）全绿 |
| **T11** | 文档同步 | 0.3 | 设计方案 §14 回填 B2 落地；`使用教程`（SOAP 小节 + curl）；`整体测试方案`（X-S 用例组）；`CLAUDE.md` Gotchas（继承陷阱 / 热路径改动纪律） | 四处同步完成 |

> **依赖顺序**：T1 → T2/T3（可并行）→ T4 → T5/T6/T7 → T8/T9 → T10 → T11。
> **T4 是唯一碰热路径的任务**，必须先完成 §4 的回归用例（15）再动 T5。

---

## 4. 自动化测试点

### 4.1 用例矩阵（对应设计方案 §10.1 编号）

| 来源 | 用例 | 类型 | 断言要点 |
|---|---|---|---|
| §10.1-6 | SOAP 1.1 出站 | 集成 | Envelope/Body + 业务元素 + `text/xml` + **`SOAPAction` 存在** |
| §10.1-7 | SOAP 1.2 出站 | 集成 | env ns `2003/05` + `application/soap+xml; action="…"` + **无 SOAPAction** |
| §10.1-8 | 响应解包 | 集成 | `data` 直接是业务字段（无 Envelope 层级） |
| §10.1-9 | **Client Fault → 死信不重试** | 集成 | `DEAD_LETTER` + `50203` + **上游请求计数=1** + **熔断 CLOSED** |
| §10.1-10 | Server Fault → 补偿 | 集成 | `COMPENSATING` + `50201` + 熔断计失败 |
| **样本 C-2** | **`VersionMismatch` → 死信不重试** | 集成 | 夹具 `fault/hello-versionmismatch-11only.*` → `DEAD_LETTER` + `50203` + **不重试、不计熔断**（与 Client 类同组） |
| **样本 C-3** | 业务错误走 200 不误判 | 集成 | 夹具 `success/w3schools-bizerror-200.*` → **不是 Fault**；不得走 `50203` 分支（由 RESP/信封判定负责） |
| **样本 C-4** | 响应 ns ≠ 请求 ns 仍能解包 | 集成 | 夹具 `success/mnb-rates-11.*` → 解包成功（不要求命名空间匹配） |
| §10.1-15 | **非 SOAP 5xx 回归** | 集成 | 行为**与改造前一致**（COMPENSATING + 短重试）—— 防"改热路径改坏" |
| §10.1-16 | faultstring 可见 | 集成 | 死信 `reason` 含供应商原文（修 C1） |
| §10.1-19 | **继承关系契约** | 单测 | `SoapClientFaultException` **不是** `HttpServerErrorException` 子类；且状态链"短重试次数"=0 |
| §10.1-23 | 前置编排 Fault | 集成 | 前置目标 Client Fault → `50203`，**不得** `40001 CONFIG_ERROR` |
| §10.1-24 | 截断与取证 | 单测 | 输入实测 S3 的 ~1.5KB 堆栈 → `reason` ≤2000 且含 `…[截断，原长 N]`；`payload` = 原始响应体 |
| §10.1-25 | 指标结局 | 集成 | `outcome=upstream_fail`（非 `transport_fail`） |
| §10.1-12 | 解码方向回归 | 单测 | 入站报文 1.0 / 1.1 / 无声明 / GBK 均能解（**入站不解包**） |

### 4.2 反证用例（本项目纪律：钉住"不可回退"）

1. **去掉熔断排除/继承关系写错** → 用例 9 的"熔断仍 CLOSED"+ 计数=1 必红；
2. **去掉 `PreStepExecutor` 分支** → 用例 23 必红（会变 `40001`）；
3. **去掉 5xx 带 body** → 用例 16 必红（faultstring 又丢失）;
4. **T1 的"soap 未知键"正向化**：B1 时代该用例断言"soap 段被拒"；B2 必须把它**改成"1.1 解析成功"**，
   否则"实现没接线"也能全绿。

### 4.3 测试隔离纪律（照 CLAUDE.md，**漏一项就偶发红**）

```java
@SpringBootTest(properties = {
    "app.api-center.auth.enabled=false",                              // 直连管理面 HTTP 的类
    "app.api-center.retry-worker-fixed-delay-ms=3600000",
    "app.api-center.retry-worker-initial-delay-ms=3600000",
    "app.api-center.alert-worker-fixed-delay-ms=3600000",
    "app.api-center.alert-worker-initial-delay-ms=3600000"
})
```
SOAP 用例涉及**重试 + 熔断**，隔离尤其重要（否则与 worker `scan()` 抢跑 → 单跑绿、全量偶发红）。

---

## 5. 手动验收方案（约 25 分钟）

### 阶段一：技术验证（`dneonline`，**免密钥**，G1 缺席时的替代）

| 步 | 操作 | 判据 |
|---|---|---|
| 1 | 建应用 `DNESOAP`（base_url `http://www.dneonline.com`）+ 分组 + 出站接口：`POST /calculator.asmx`，`root=Add`，`namespace={prefix:"",uri:"http://tempuri.org/"}`，`soap={version:"1.1",action:"http://tempuri.org/Add"}`，入站参数 `intA`/`intB`(number)，RESP `AddResult`(number) | 保存成功（无 40001） |
| 2 | 调用（JSON 进 → SOAP 出） | 200 + `data.AddResult=3`；调用日志 OUT 条含完整 SOAP 报文 |
| 3 | 把「XML 类型」改为 **SOAP 1.2** 再调 | 200；OUT 条 env ns 为 `2003/05`、`Content-Type` 带 `action=`、**无 SOAPAction** |
| 4 | **Fault 路径**：`intA` 传非数字 | `50203`；**运行记录仅 1 条**、**无短重试**；死信 `reason` 含 `faultcode=Client` + 供应商原文（截断后）；**熔断面板仍 CLOSED** |
| 5 | 对照直连 curl（§10.2 已给） | 直连也 500 + `soap:Client` → 证明是供应商行为而非平台问题（**二分基线**） |

### 阶段二：真实供应商验收（**G1 就绪后必做**）

| 步 | 操作 | 判据 |
|---|---|---|
| 6 | 按真实 WSDL 配接口（`action`/`envelopePrefix`/命名空间/`root`） | 保存成功 |
| 7 | 正常业务调用 | 供应商返回预期结果；核对 OUT 条报文**服务端不再报解析错** |
| 8 | 构造业务错误 | 分类正确（Client→死信 / Server→补偿）；`faultstring` 可见 |
| 9 | 若供应商**要求 Header** | ⚠️ **会失败**（§1.2 不做 Header）→ 记录为 backlog 触发条件 |

### 阶段三：回归与回退

| 步 | 操作 | 判据 |
|---|---|---|
| 10 | 灰度 S0b：**只上"5xx 带 body 抛"**、不改分类 | 非 SOAP 5xx 行为不变（用例 15）；死信 reason 开始有供应商原文 |
| 11 | S3/S4：启用 SOAP → Fault 演练 | 用例 6/8/9/10/16 通过 |
| 12 | 回退：清空该接口 `protocol_params`（或版本回滚） | 回落内置默认（非 SOAP 行为）；**无重启、无开关** |

---

## 6. 出口标准与验收点（B2 完成 = 全部勾选）

- [ ] T1–T11 全部完成，任务级验收点逐条通过
- [ ] **§4.1 全部用例绿** + **§4.2 四条反证绿**
- [ ] **全量测试全绿**（325 + B2 新增，含 `mvn -o clean test-compile`）
- [ ] 前端 `npm run lint` / `npm test` / `npm run build` 三者通过
- [ ] **§5 阶段一 + 阶段二均通过**（阶段二需 G1；仅有阶段一**不宣称 B2 完成**）
- [ ] 灰度 S0b 单独验证过（"改热路径"与"改分类"分开）
- [ ] 文档同步四处完成（§3 T11）
- [ ] 已知边界写入文档：无 Header、无 1.2 `200+Fault`、无 ack SOAP 化、无原生编码字节

---

## 7. 文档同步与风险

### 7.1 文档同步义务

| 文档 | 内容 |
|---|---|
| `doc/开发文档/XML声明配置设计方案.md` | §14 回填 B2 落地记录（改动清单 + 验证 + 实现期发现） |
| `doc/API中心使用教程.md` | §4.4 协议参数小节补 SOAP 子项（1.1/1.2、action、envelopePrefix、unwrap）+ curl |
| `doc/开发文档/整体测试方案.md` | §6.6 加 **X-S 组**（SOAP 1.1/1.2/Fault/解包，4~6 例） |
| `doc/API中心设计方案.md` | §6.2 错误码表加 `50203`；§5.3/§5.5 措辞（SOAP 不再是边界） |
| `CLAUDE.md` | Gotchas：继承陷阱 / 热路径改动纪律 / Fault 分类口径 |

### 7.2 风险与应对

| # | 风险 | 等级 | 应对 |
|---|---|---|---|
| **R1** | 改 `UpstreamInvoker` 热路径引发重试/熔断回归 | **中高** | ① 改造**不改变**非 SOAP 5xx 行为；② **先做用例 15** 再动 T5；③ 灰度 **S0b 单独成步** |
| **R2/R9** | `SoapClientFaultException` 误继承 → ①继续重试 ②计熔断失败（**双重错**） | **中高** | §2.4-1 契约 + 用例 19 钉住类型层次 + 用例 9 断言熔断 CLOSED |
| **R11**（样本新增） | **Fault 分类漏 `VersionMismatch`/`MustUnderstand`** → 确定性配置错误被当服务端故障**无限补偿** | 中高 | §2.3 穷举 4 个 faultcode + 新增样本用例（C-2） |
| **R10** | `PreStepExecutor` 漏处理 → 被归 `40001 CONFIG_ERROR` | 中高 | §2.4-3 + 用例 23 |
| **R3** | 真实样本与保守取舍不符（`D-SOAP-8`/`envelopePrefix`/`action`） | ~~中~~ **已闭环** | **G1 已拿到并核对完毕（2026-09-21）**：`action` → 改可选（C-1）；`envelopePrefix` → 维持可配（样本前缀不一致，O-2/O-3 再次印证不能认前缀）；`D-SOAP-8` → 改判分两步（§2.7） |
| R4 | Fault 探测误判（非 SOAP 报文的 5xx 里含 `<Fault`） | 低 | 判据 = `soapVersion` 非空 **且 结构匹配** `Body/Fault`（不做字符串嗅探） |
| R5 | 1.2 的 `action` 为空 | 低 | ~~T1 保存期 40001~~ → **已撤销（C-1）**：实测 6/6 服务不强制 action，改为**可选** |
| R6 | 解包导致"成功 + 空 data"（F-3 同类失效） | 低 | 与 backlog §13-8（warnings 暴露到信封）**同批评估** |
| — | **回退** | — | 清空/回滚 `protocol_params` → 回落内置默认（非 SOAP），**无重启、无开关** |

### 7.3 与 B1 的衔接清单（开工第一小时照做）

1. `XmlProtoConfig`：`soap` 从"未知键"移入白名单（含 `KNOWN_SOAP_KEYS`），删掉"B2 延后"提示；
2. 对应单测 `XmlProtoConfigTest#soap段_明确拒绝并给出延后提示` → **改为** `soap段_解析成功`（§4.2-4，否则"没接线也能全绿"）；
3. `OutboundRequestSpec` 加 `soapVersion`（B1 未加）；
4. 设计文档 §2 非目标 ④/§3.2 D-SOAP-6~9 的状态从"延后"改为"实施中"。


---

## 8. 落地记录（v1.0 · 2026-09-21：**与样本无关部分已实施**）

> 本次按用户拍板的 **①+③** 执行：**① 出 §2.5.1 界面逐控件验收清单**；
> **③ 先做与样本无关的 B2 主体（T1–T9）并用真实 SOAP 服务验证**。G1（企业级 Header 样本）仍未取得，
> 但 **§1.2 已把 Header 列为非目标** ⇒ **不阻塞 T1–T9**。

### 8.1 实际改动（逐任务）

| 任务 | 状态 | 实际改动 |
|---|---|---|
| **T1 配置解析** | ✅ | `XmlProtoConfig` 重构：新增 `Type` 枚举（`POX`/`SOAP_1_1`/`SOAP_1_2`，携带 soapVersion 与 envelopeNs）与 `SoapConfig`（action/envelopePrefix/unwrapResponse，**不含 version**）；`of()` 校验：`type` 白名单、**`POX`+`soap` → 40001 互斥**、`soap` 内未知键（含旧写法 `version`）→ 40001；便捷工厂 `pox(...)`/`soap(...)` |
| **T2 请求包裹** | ✅ | `XmlProtocolAdapter.encode`：`Envelope→Body→业务元素`（三参 API 写命名空间）；`applyXmlHeaders` 按类型分派（1.1: `text/xml`+`SOAPAction`；1.2: `application/soap+xml; action=`）；**给 `spec` 置 `soapVersion`**；**`ackMode` 属性**保证 ack 不被包裹（D-SOAP-6） |
| **T3 响应解包** | ✅ | `unwrapSoapIfConfigured`：取 `Body` 内第一个元素为业务根（宽容失败 + warn）；**只在响应方向生效**（入站不注入配置，Q17 自然成立）；`unwrapResponse=false` 时保留层级 |
| **T4 5xx 带 body + Fault 探测** | ✅ | `SoapFaultParser`（**新**，JDK DOM + XXE 加固）：按**报文自身 envelope 命名空间**判 1.1/1.2 → `faultcode/faultstring` 或 `Code/Value`+`Reason/Text`；4 个客户端码（Client/Sender/VersionMismatch/MustUnderstand）→ `SoapClientFaultException`；`UpstreamInvoker.invoke` 5xx 改为 `create(status, …)` **带 body**（修 C1） |
| **T5 引擎分类** | ✅ | `SoapClientFaultException extends RuntimeException`（**新**，带类型层次契约测试）；`OutboundEngine` instanceof 链**最前**分支 → `classifySoapClientFault`（死信 + `50203` + payload=原始响应体 + reason 截断前 500）+ **不计熔断失败** |
| **T6 前置编排** | ✅ | `PreStepExecutor` 新增 `catch (SoapClientFaultException)` → `HTTP_5XX` 出口 + `50203`（不新增出口） |
| **T7 指标口径** | ✅ | `CallLogAspect.outcomeOfOut`：该异常归 **`upstream_fail`**（非 transport_fail） |
| **T8 错误码** | ✅ | `BizException.SOAP_CLIENT_FAULT = 50203` |
| **T9 前端** | ✅ | `XML_TYPES`（**普通 XML（POX）/ SOAP 1.1 / SOAP 1.2**）+ 类型下拉 + 按类型切换 SOAP 子项 + `rootFieldLabel()` 动态标签（「根元素」↔「Body 内业务元素」）+ `buildProtocolParams` 支持 type/soap（POX 绝不输出 `soap`） |
| T10 测试 | ✅ 部分 | 新增/改写单测：`XmlProtoConfigTest`（+8）、`SoapFaultParserTest`（**新 9**，全部用仓库真实夹具）、`SoapClientFaultExceptionTest`（**新 2**）、`XmlProtocolAdapterTest`（+7）；前端 `protocolParams.test.mjs`（+4）；**全量 351 全绿** |
| T11 文档 | 🟡 部分 | 本节 + §2.5.1；**待办**：`使用教程` SOAP 小节、`整体测试方案` X-S 组、`CLAUDE.md`、设计总纲 §6.2 错误码表 |

### 8.2 真机验证（真实 SOAP 服务，非桩）

| 用例 | 期望 | **实测** |
|---|---|---|
| SOAP **1.1** 成功（NumberConversion，HTTPS） | 200 + 业务字段 | ✅ `data.NumberToWordsResult="one thousand two hundred and thirty four"`；头 `text/xml; charset=UTF-8` + `SOAPAction: "…"`；envelope ns `schemas.xmlsoap.org` |
| SOAP **1.2** 成功（同端点） | 200 | ✅ `"four thousand three hundred and twenty one"`；头 `application/soap+xml; charset=UTF-8; action="…"`、**无 SOAPAction**；envelope ns `2003/05` |
| 响应**解包** + 前缀剥离 | 业务字段直接在 `data` 根 | ✅ 上游用 `m:` 前缀（`<m:NumberToWordsResponse>`）仍正确解出 |
| **VersionMismatch**（1.2 → 1.1-only 服务） | 死信 + 不重试 + 不熔断 | ✅ `502` + `code=50203` + 死信；**「出站状态机」尝试数 = 1（零重试）**；链 `MAPPING→DEAD_LETTER`；**熔断 = 0.0（CLOSED）**；指标 `outcome=upstream_fail`；死信 reason 含 `soap:VersionMismatch` + 供应商原文 |
| **POX 回归**（type 缺省） | 出站无 Envelope | ✅ `<?xml …?><NumberToWords xmlns="…"><ubiNum>7</ubiNum></NumberToWords>` |

> ⚠️ **取证口径更正（2026-09-21 复核）**：`CallLogAspect` 带 `@Order(HIGHEST_PRECEDENCE)`，**位于 `@Retryable` advisor 之外**
> ⇒ **每次 HTTP 尝试各落一条**：本次"1 条"说明确实没有重试（**条数 = 尝试次数**，"恰 1 条" ⇔ 零重试）。
> ⚠️ 这与 D-M4-4「每业务请求恰一条 OUT」的定稿**不一致**（2026-09-22 实测发现，待拍板）；
> 正确判据是 `outbound_request.attempt_count`（页面：**出站状态机 Tab 的「尝试」列**）。
> 本次结论（零重试）本身仍成立 —— 由 `@Retryable.includes` 白名单机制 + `SoapClientFaultExceptionTest` 钉死。

### 8.3 实现期发现（新，已处置）

| # | 发现 | 说明 / 处置 |
|---|---|---|
| **F-B2-1** | **部分主机/WAF 按 User-Agent 拒给 Java 客户端**（实测 `dneonline.com`：`User-Agent: Java/…` 或 `Java-http-client/…` → **连接重置**；`Apache-HttpClient` / `Mozilla` / curl → 200） | JVM 默认 UA 就是 `Java-http-client/…` ⇒ **平台默认会被这类主机重置**（表现为 `ResourceAccessException` → UNKNOWN，极易误判为“超时”）。影响：用 dneonline 做验证时改用其它真实端点（NumberConversion / CountryInfo / LearnWebServices 均不限制 Java UA）。**建议（backlog）**：新增可配默认 `User-Agent`（或接口级静态头，属既有 backlog） |
| **F-B2-2** | **传输异常日志只打异常类名**，`ResourceAccessException` 下无法区分「连接超时 / 读超时 / **连接重置** / 协议错」 | 已修：`OutboundEngine` 补 `rootCauseOf(e)`（class: message）→ 本次正是靠它一步定位到 `SocketException: Connection reset` |
| **F-B2-3** | “既有调用点零改动”再次不成立（同 I-2）：B1 测试的 `new XmlProtoConfig(5 参)` 因重构而编译失败 | 已改为 `XmlProtoConfig.pox(...)` 便捷工厂；**全仓扫过方法名里不能有全角标点**（踩了两次） |

### 8.4 仍未做（保持 B2 未完成）

- **`Header` 仍未实现**（`D-SOAP-8` 已拍板分两步：静态 Header = **B2.1**、密码学 Header = **B3**，见 §2.7）；
- T11 的 4 处文档同步（见上）；
- `dneonline` 因 F-B2-1 不作为验证锚点（保留其**样本**作 WireMock 夹具仍有效）。

### 8.5 G1 关闭与 Header 定口径（2026-09-21）

| 项 | 内容 |
|---|---|
| **缺口** | 企业级 `soap:Header` / WS-Security 样本（公开样本 **0/6**，属抽样偏差） |
| **取得路径** | **Amadeus**（航空 GDS，真·企业级 SOAP）的**公开客户端仓库**测试夹具：WSDL 真实声明 `<soap:header message="ses:Session" part="Session" use="literal"/>`；真实报文含 **3 种 Header 家族**（WS-Addressing / WS-Security `UsernameToken`+`PasswordDigest` / 厂商 `Session` 与自定义属性头） |
| **入库形态** | `src/test/resources/soap-samples/header/`：**6 个手写合成夹具**（保留结构与命名空间，**值全为假值**）+ README；Amadeus 的 WSDL/XSD 原文**未入库**（其标注 "Proprietary and Confidential"） |
| **产出结论** | `D-SOAP-8` 由“不做”改为 **“分两步”**（§2.7）；并收获 5 条观察 **O-1~O-5**（真实报文可能 schema-invalid；同一 Header 请求带前缀、响应不带；冗余/内联前缀；语义靠属性；Header 会演进） |
| **对本期影响** | **零**（T1–T9 范围不变，不新增任务）；B2.1/B3 属后续排期 |

---

## 附：变更记录

- v1.0（2026-09-21）初稿；同日修订：`action` 改为可选（样本 C-1）、Fault 分类**穷举** `VersionMismatch`/`MustUnderstand`（C-2）、新增 §2.5 类型选择器（方案 A）+ §2.5.1 逐控件验收清单、新增 §8 落地记录（与样本无关部分已实施并真机验证）；**随即 G1 关闭**（企业级 Header 样本经 Amadeus 公开客户端仓库取得 → `soap-samples/header/`）+ 新增 **§2.7**（`D-SOAP-8` 拍板：Header 分两步）+ **§8.5** 记录。


