# 企业级 SOAP Header 样本（**G1 关闭证据**，2026-09-21）

> **这批文件的作用**：回答一个此前只能靠猜的问题 —— **真实企业级 SOAP 的 `soap:Header` 到底长什么样、平台要不要做、做到什么程度。**
> 它决定了 `D-SOAP-8`（本期是否写 Header）。此前 `G1` 因"公开样本 0 命中"而挂起；本目录即为关闭依据。

## 来源与版权口径

| 项 | 说明 |
|---|---|
| **形态来源** | **Amadeus Web Services**（航空 GDS，真·企业级 SOAP）的**公开客户端仓库** `amabnl/amadeus-ws-client`（测试夹具目录），其 WSDL/XSD 由 Amadeus 面向接入方公开发布 |
| **本目录文件性质** | **全部为我方手写的“合成夹具”** —— 保留真实报文的**结构与命名空间**，**所有值替换为假值**（`USERID` / `MONEY` 式占位） |
| **未入库** | Amadeus 的 WSDL / XSD **原文未入库**（Amadeus 标注 "Proprietary and Confidential"）；本文只引用其**结构事实**与最小片段 |
| **参考 URL** | WSDL：`https://raw.githubusercontent.com/amabnl/amadeus-ws-client/master/tests/Amadeus/Client/Session/Handler/testfiles/soapheader2/testwsdlsoapheader2.wsdl`；真实带 Header 报文：同目录 `dummyPnrRequest.txt` / `dummyPnrRequestsoapheader2.txt` / `dummySecurityAuthReply.txt` |

> 版权纪律：**若将来需要接入 Amadeus 类供应商**，自研实现应只依据其**公开发布的 WSDL/XSD**编写，不要复制其私有 schema 文本入库。

## 为什么这一批样本重要：样本偏差的实证

| 样本来源 | 数量 | WSDL 声明 `soap:header` |
|---|---|---|
| 公开免密钥玩具/公益服务（本目录上一级 6 个） | **6** | **0 / 6** |
| **企业级**（Amadeus） | **1** | **1 / 1**（且**服务端会在响应里回传 Header**） |

⇒ **"公开样本无 Header"是抽样偏差，不是行业现状**。B2 把 Header 列为非目标时，依据是"6/6 未声明"——现在这条依据**被推翻**，必须改为**分两步的显式决策**（见下）。

## 三种 Header 家族（真实报文归纳）

| # | 家族 | 载体 | 值从哪来 | 平台能否用「静态配置」覆盖 | 夹具 |
|---|---|---|---|---|---|
| **①** | **WS-Addressing 1.0** | `wsa:MessageID` / `wsa:Action` / `wsa:To` | `Action`/`To` 基本固定；`MessageID` 每次可变 | ✅ **能**（`Action`/`To` 填静态值；`MessageID` 可留空或后续用变量） | `wsa-addressing-11.req.xml` |
| **②** | **WS-Security `UsernameToken` + `PasswordDigest`** | `wsse:Security` → `Username` / `Nonce` / `Password` / `wsu:Created` | **动态密码学构造**：`PasswordDigest = Base64(SHA1(nonce_bytes ‖ created_text ‖ password))`，`Nonce`/`Created` **每请求都变** | ❌ **不能**（静态配置**不可能**覆盖，必须有"头构造器"） | `wsse-usernametoken-digest-11.req.xml` |
| **③-a** | **厂商会话头**（`Session`） | `SessionId` / `SequenceNumber` / `SecurityToken` | 登录一次取得，后续请求带上（半静态，可能需递增 `SequenceNumber`） | ✅ **基本能**（值可来自凭证/配置；`SequenceNumber` 递增属增强） | `vendor-session-11.req.xml`<br>`vendor-session-11.resp.xml` |
| **③-b** | **厂商自定义头**（**靠属性承载**） | `SecurityHostedUser/UserID`，语义全在**属性**上（`PseudoCityCode` / `AgentDutyCode` / `RequestorType`） | 静态（机构/坐席标识） | ✅ **能**，但**属性必须能表达**（元素本体是空的） | `vendor-custom-attrs-11.req.xml` |
| — | **WSDL 侧声明形态** | `<soap:header message="ses:Session" part="Session" use="literal"/>`（Header 有**独立 message**） | — | 设计参考 | `wsdl-soap-header-declaration.xml` |

## 对 `D-SOAP-8` 的结论：**分两步**（本期 + B3）

**第一步（= B2.1，紧随 SOAP 主体；**口径已定、未排期**）—— 静态 / 占位符 Header**
- 配置化描述一棵 **Header 元素树**（元素名 + 命名空间/前缀 + 文本值 + **属性**），值可引用**凭证字段**或入参；
- 覆盖 **① WS-Addressing** 与 **③ 厂商 `Session` / `AMA_*` 类**；
- 边界：**不做**签名、**不做** Nonce/时间戳派生 → 明确写入"不支持清单"，配了不支持的形态要 **`40001`**（不静默忽略，同 B1 纪律）。

**第二步（= B3，真有客户要再做）—— 密码学 Header 构造器**
- `UsernameToken` + `PasswordDigest`（+ 可选 `Timestamp` / 签名 / `MustUnderstand` 语义）；
- **它是"有状态的密码学构造"，不是"填一段 XML"** —— 需要每请求生成 `Nonce`/`Created` 并计算摘要，属独立能力（与鉴权适配器同族，但写 Header 而非 `Authorization` 头）；
- 实现前提：`@Retryable` 重试时**同一请求必须复用同一 `Created`/`Nonce`**（否则重试会被判重放）——这条要在 B3 设计时钉死。

> **本期不做的判定不变**：`Header` 仍不在 B2 编码范围内（T1–T9 不含），本目录只**关闭 gate 并定下口径**，不新增任务。

## 实现期观察点（真实报文里发现的，五条）

| # | 观察 | 对我们的含义 |
|---|---|---|
| **O-1** | 真实报文里的 `wsu:Created` 值写作 `2016-01-27T16:40:36**:**497Z`（毫秒用 `:` 而非 `.`）—— **不合 `xs:dateTime`** | **真实企业报文也可能 schema-invalid** ⇒ 我们"宽容解析 + 不因个别字段不合法就拒收"的口径是对的（同 B1 `encoding` 语义 A 的取舍） |
| **O-2** | **同一个 `Session` 头，请求侧带前缀**（`<ns2:Session>`），**响应侧用默认命名空间**（`<Session>`） | 解析必须按**命名空间/localName**，**绝不能认前缀**（与 C-4 一致；也印证 B1"前缀只是标签"的设计） |
| **O-3** | 该报文声明了 `xmlns:ns3`（WSSE ns）却**从未使用**，真正用的是元素上**内联声明**的 `oas:` 前缀 | 真实报文的前缀生态很脏（冗余/内联/不一致）⇒ 任何按前缀文本匹配的启发式都会翻车 |
| **O-4** | 厂商头完全可能**靠属性**承载全部语义（`UserID POS_Type="1" PseudoCityCode="OFFICE" …`），元素本体为空 | 未来静态 Header 配置**必须支持属性**，只支持"元素 = 文本"不够 |
| **O-5** | **同一服务存在两代 Header 形态**（`Security_v1` 的 WSA+WSSE 版 vs `WBS_Session-2.0` 的 `Session` 版） | 供应商会**演进 Header** ⇒ 配置要能**整体替换**，不能只"追加一条"（否则新旧混发） |

## 文件索引

| 文件 | 内容 |
|---|---|
| `wsdl-soap-header-declaration.xml` | WSDL 中声明 `soap:header` 的最小可解析片段（Header 有独立 message） |
| `wsa-addressing-11.req.xml` | 家族①：WS-Addressing（SOAP 1.1） |
| `wsse-usernametoken-digest-11.req.xml` | 家族②：WS-Security UsernameToken + PasswordDigest + Nonce + Created |
| `vendor-session-11.req.xml` | 家族③-a：厂商会话头（请求侧，前缀形式） |
| `vendor-session-11.resp.xml` | 家族③-a 响应侧（默认命名空间形式 —— 对应 O-2） |
| `vendor-custom-attrs-11.req.xml` | 家族③-b：厂商自定义头（语义靠属性 —— 对应 O-4） |

> **本目录不参与自动化测试**（`Header` 未实现，无可断言行为）——它是**设计依据 + 未来 B3 的用例种子**。
> 与上一级目录的区别：上一级是**真实服务实录**（可复现、可回放），本目录是**形态合成**（不可调用，只描述结构）。
