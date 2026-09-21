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
| **G1** | **真实 SOAP 供应商的 WSDL / 请求响应报文样例** | ❌ **未取得**（这是 Q19 延后的直接原因） |
| G2 | B1 已上线且稳定 | ✅ 已落地（325 测试全绿） |
| G3 | 该供应商可联调（测试环境可达、可开关） | ❌ 待确认 |

> **G1 缺席时的替代方案（仅技术验证，不作为验收锚点）**：用公网 `dneonline.com`（已实测 S1/S3/S4，免密钥）
> 先把 T1–T8 做完并跑通；**但不宣称 B2 完成**——因为 `D-SOAP-8`（不写 Header）、`envelopePrefix`
> 默认值、`action` 是否需要等决策都是**无样本下的保守取舍**，极可能与真实供应商不符。

### 1.2 不做什么（范围纪律，越界即评审）

| 不做 | 理由 / 去向 |
|---|---|
| **SOAP `Header` 自定义内容** | 无真实样本；需"Header 模板"能力（模板语法/变量注入/安全边界）→ backlog §13-1 |
| **SOAP 1.2 的 HTTP 200 + Fault** | 罕见；补它要改 `ResponseJudger`（**被前置编排共用**）→ backlog §13-2 |
| **平台对外暴露 SOAP 端点**（服务端 SOAP） | `protocol_in` 是平台自家契约；Q17 已定**入站不解包** → backlog §13.1 #11 |
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

### 2.2 配置形态（B1 同一列，增量加 `soap` 段）

```jsonc
{ "xml": {
    "version": "1.0", "encoding": "UTF-8",
    "root": "Add",                                  // SOAP 下 = <Body> 内业务元素
    "namespace": { "prefix": "", "uri": "http://tempuri.org/" },
    "soap": {                                       // ← B2 新增；出现即启用 SOAP 包裹
      "version": "1.1",                             // 1.1 | 1.2
      "action":  "http://tempuri.org/Add",
      "envelopePrefix": "soap",                     // 默认 soap（1.2 常用 env）；前缀本身无语义
      "unwrapResponse": true                        // 响应解包，默认 true
    }
} }
```

> **B1 遗留的过渡**：B1 把 `soap` 段列为**未知键 → 40001**（提示"B2 延后"）。B2 开工第一件事就是
> 把它从 `KNOWN` 之外移入白名单（`XmlProtoConfig.KNOWN_XML_KEYS` + 新增 `KNOWN_SOAP_KEYS`），并去掉那句提示。

### 2.3 Fault 分类（**B2 的核心价值**，实测 S3）

| faultcode | 语义 | 终态 | 错误码 | 短重试 | 补偿 | 熔断计数 |
|---|---|---|---|---|---|---|
| `Client` / `Sender` | **我们请求错了**（参数/格式/权限） | **DEAD_LETTER** | **`50203`（新增）** | ❌ | ❌ | ❌ **不计失败** |
| `Server` / `Receiver` | 供应商服务端故障 | COMPENSATING | `50201` | ✅ | ✅ | ✅ 计失败 |
| 非 Fault 的纯 5xx | 保持现状 | COMPENSATING | `50201` | ✅ | ✅ | ✅ 计失败 |

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

### 2.5 开工前复核的 3 个技术事实（已实测，实现时对齐）

| # | 事实 | 证据 |
|---|---|---|
| 1 | `@Retryable.includes` 默认 `[]`（项目显式列 3 类 → 白名单收窄） | `javap` 注解默认值 |
| 2 | `HttpServerErrorException.create(status, text, headers, body, charset)` 可带 body | `javap` Spring 7.0.8 |
| 3 | 三参 `writeStartElement(prefix,local,ns)` + `writeNamespace` 才能正确输出 xmlns | Woodstox 探针 #2/#3 |

---

## 3. 任务拆解（**6.4 人日**）与任务级验收点

| # | 任务 | 人日 | 工作项 | 验收点（完成的可验证标志） |
|---|---|---|---|---|
| **T1** | SOAP 配置解析与校验 | 0.3 | `XmlProtoConfig` 加 `soap` 段：`version ∈ {1.1,1.2}`；**1.2 时 `action` 必填**；`envelopePrefix` 合法前缀；`unwrapResponse` 布尔；未知键仍 40001 | 单测：1.1/1.2 解析、1.2 缺 action→40001、`soap` 内拼错键→40001、B1 的"soap 段拒绝"用例**改为正向** |
| **T2** | 请求包裹（Envelope/Body + 命名空间） | 0.8 | `XmlProtocolAdapter.encode` 在 `soap` 存在时先写 `Envelope`→`Body`，业务元素按 `root`/`namespace` 写在 Body 内；`Content-Type` 按 1.1/1.2 分派；1.1 加 `SOAPAction` 头 | 集成：1.1 报文含 Envelope/Body + `SOAPAction` 头；1.2 报文 env ns 为 2003/05 + `Content-Type` 带 `action=` 且**无** `SOAPAction` 头 |
| **T3** | 响应解包（**仅响应方向**，Q17） | 0.5 | `decode` 判据：根 localName==`Envelope` **且**有 `Body` → 取 Body 内第一个元素为业务根；否则原样 + `log.warn`；**入站请求方向不解包**（B1 已注 | 集成：供应商返回 Envelope → `data` **不含 Envelope 层级**（用例 8）；用例 12 回归（入站解码不受影响） |
| **T4** | `UpstreamInvoker` 5xx 带 body 抛 + Fault 探测 | 1.0 | `OutboundRequestSpec` 加 `soapVersion`（ENCODE 时置位）；5xx 分支改 `HttpServerErrorException.create(…, body …)`；**结构匹配** `Body/Fault`（非字符串嗅探）且 `soapVersion` 非空时：Client/Sender → `throw SoapClientFaultException`（**直接继承 RuntimeException**） | 单测/集成：5xx 的 faultstring 可见（用例 16）；**反证**：非 SOAP 5xx 行为不变（用例 15） |
| **T5** | `OutboundEngine` Fault 分类 | 0.8 | instanceof 链**最前**加分支 → 写 `dead_letter`（payload = 原始响应体；reason 截断）→ `DEAD_LETTER` + `50203`；**不进** `record(false)`（熔断不计失败） | 集成：Client Fault → 终态 `DEAD_LETTER`/`50203`、**WireMock 计数=1**（不重试）、**熔断仍 CLOSED**（用例 9）；Server Fault → `COMPENSATING`/`50201`（用例 10） |
| **T6** | `PreStepExecutor` 分支（易漏点） | 0.3 | 新增 `catch (SoapClientFaultException)` → `50203`（复用 `Kind.HTTP_5XX`，**不新增出口**） | 集成：前置目标返回 Client Fault → 分类为 SOAP 客户端错误 + `50203`，**不得**是 `40001 CONFIG_ERROR`（用例 23） |
| **T7** | 指标口径 | 0.2 | `CallLogAspect.outcomeOfOut` 把该异常归 **`upstream_fail`**（与 4xx 一致，**不是** `transport_fail`） | 集成：`apicenter.gateway.requests{outcome=upstream_fail}`（用例 25） |
| **T8** | 错误码登记 | 0.1 | 《API中心设计方案.md》§6.2 502xx 段加 `50203`（语义：SOAP Fault（客户端错误，不重试）） | 错误码表与 `BizException` 常量一致、与 50201/50202 语义不重叠 |
| **T9** | 前端 SOAP 子表单 | 0.6 | 接口「高级 → 协议参数」在 XML 下增加 SOAP 开关 + `version`/`action`/`envelopePrefix`/`unwrapResponse`；`utils/protocolParams.mjs` 扩展（含单测） | 前端 `npm run lint` 0 error + `npm test` 全绿 + `npm run build` 通过；界面勾 SOAP 后才显示子项 |
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
| 3 | 改 `soap.version=1.2` 再调 | 200；OUT 条 env ns 为 `2003/05`、`Content-Type` 带 `action=`、**无 SOAPAction** |
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
| **R10** | `PreStepExecutor` 漏处理 → 被归 `40001 CONFIG_ERROR` | 中高 | §2.4-3 + 用例 23 |
| **R3** | 真实样本与保守取舍不符（`D-SOAP-8`/`envelopePrefix`/`action`） | **中** | G1 拿到后**先按真实报文核对 4 项**再开 T2；差异按 backlog 排序 |
| R4 | Fault 探测误判（非 SOAP 报文的 5xx 里含 `<Fault`） | 低 | 判据 = `soapVersion` 非空 **且 结构匹配** `Body/Fault`（不做字符串嗅探） |
| R5 | 1.2 的 `action` 为空 | 低 | T1 保存期 40001 |
| R6 | 解包导致"成功 + 空 data"（F-3 同类失效） | 低 | 与 backlog §13-8（warnings 暴露到信封）**同批评估** |
| — | **回退** | — | 清空/回滚 `protocol_params` → 回落内置默认（非 SOAP），**无重启、无开关** |

### 7.3 与 B1 的衔接清单（开工第一小时照做）

1. `XmlProtoConfig`：`soap` 从"未知键"移入白名单（含 `KNOWN_SOAP_KEYS`），删掉"B2 延后"提示；
2. 对应单测 `XmlProtoConfigTest#soap段_明确拒绝并给出延后提示` → **改为** `soap段_解析成功`（§4.2-4，否则"没接线也能全绿"）；
3. `OutboundRequestSpec` 加 `soapVersion`（B1 未加）；
4. 设计文档 §2 非目标 ④/§3.2 D-SOAP-6~9 的状态从"延后"改为"实施中"。
