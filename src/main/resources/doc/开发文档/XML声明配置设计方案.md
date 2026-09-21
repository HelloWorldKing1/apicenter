# XML 声明 / 命名空间 / SOAP 配置设计方案（v4.3 · 2026-09-21）

> **v4.3 = B1 已落地**（**代码已实现并验证**）：Q17/Q18/Q19 均按 v4.2 拍板执行；
> **B1 全量完成**（后台 + 前端 + 测试），**B2（完整 SOAP）仍延后**。落地与验证记录见 §14。
> 历次修订：v1.0 平台级 adapter 实例方案（**已废弃**）→ v2.0 接口级 `protocol_params` + `encoding`
> + 命名空间/根元素 → v3.0 **完整 SOAP** → v4.0 定稿 → v4.1 自审修正 16 项 → v4.2 决策闭合 → **v4.3 B1 落地**。
>
> **前置状态**：开发库已于 2026-09-21 清空重开，并已**重新导入 fastmoss 种子**（`POST /api/admin/seed/import`
> —— 集成测试依赖它）；备份 `~/apicenter-dev-db-backup-20260921-133111.sql`。
>
> **本方案状态**：**B1 已交付（325 测试全绿）**；B2 设计保留、未实施（触发条件见 §12 Q19）。

### TL;DR（30 秒版）

| | 内容 |
|---|---|
| **改什么** | ① `interface` 加 **1 列** `protocol_params`（JSON）② `XmlProtocolAdapter` 按配置输出 XML 声明 / 根元素 / **命名空间** / **SOAP 1.1·1.2 包裹与解包** ③ `UpstreamInvoker` 5xx **带 body 抛** + SOAP Fault 探测（修“faultstring 丢失”）④ `OutboundEngine`/`PreStepExecutor` Fault 分类（`50203` 死信不重试、不计熔断）⑤ 前端接口弹窗加「协议参数」区 |
| **不改什么** | 接口模型其余字段 / `interface_snapshot` 七段结构 / **`ResponseJudger`**（→ 前置编排共用实现不受影响）/ ack 渲染 / 解码侧校验策略 / 其他适配器类型 |
| **怎么验** | ① 未配置 ⇒ 报文与现状**逐字节一致**（用例 1）② 配**等价默认值** ⇒ 仍逐字节一致（证明接线生效，S1）③ 真实 SOAP 服务（`dneonline`，**免密钥**）跑正常路径 + Fault 路径（用例 6/8/9/10/16）④ **反证**：去掉配置读取 / 去掉熔断排除 ⇒ 必红（用例 17/18） |

> ⚠️ **一句话风险提示**：完整 SOAP 会把改动推进到**出站热路径**（`UpstreamInvoker` 的 5xx 分支）、
> **熔断计数语义**、以及**前置编排**（`PreStepExecutor`，v4.1 新发现）—— 见 §5.4 与 §8。

---

## 0. 实测证据（第一手事实，决定设计形态）

### 0.1 XML 输出能力（12 项探针，Woodstox 6.4.0 + stax2-api 4.2.1）

| # | 探针 | 结果 | 影响 |
|---|---|---|---|
| 1 | writer 类型 | `SimpleNsStreamWriter`（`isRepairingNamespaces=false`） | 命名空间**感知**，需显式声明 xmlns |
| 2 | 三参 `writeStartElement(prefix,local,ns)` + `writeNamespace` | `<soap:Envelope xmlns:soap="…">` ✅ | **命名空间与 SOAP 可行** |
| 3 | 默认命名空间（空前缀） | `<queryRequest xmlns="…">` ✅ | 两种命名空间形态均支持 |
| 4 | `writeAttribute("xmlns:g",…)` | 可行但**非标准写法** | 不作主路径 |
| 5 | `version=1.1` | `<?xml version='1.1' …?>` ✅ | version 可配成立 |
| 6 | `version=1.2` | **抛** `Illegal version argument ('1.2'); should only use '1.0' or '1.1'` | 当前被吞成 `50000` → 必须白名单 `40001` |
| 7 | 不调 `writeStartDocument` | 无声明 ✅ | `omitDeclaration` 零成本（本期不做） |
| 8 | `useDoubleQuotesInXmlDecl=true` | 双引号声明 ✅ | 引号风格 1 行（本期不做） |
| 9 | **factory encoding=GBK** | 中文 → `&#x4e2d;` 字符引用，**字节全 ASCII**（UTF-8 才是原生字节） | ⚠️ **§1 encoding 语义** |
| 10 | 专有重载 `createXMLStreamWriter(Writer,"GBK")` | **同样转义** → 产不出原生 GBK 字节 | ⚠️ 语义 B 不可达 |
| 11 | 入站：声明 `UTF-8` + GBK 字节 | **硬报错** `Invalid UTF-8 middle byte 0xd0` → `40002` | ✅ 该方向可诊断 |
| 12 | 入站：声明 `GBK` + UTF-8 字节 | **静默乱码**（`涓枃娴嬭瘯`） | ⚠️ 无法检测 → 解码侧**不加**兜底 |

### 0.2 真实 SOAP 服务验证（`http://www.dneonline.com/calculator.asmx`，公网免密钥）

| # | 验证 | 实测结果 |
|---|---|---|
| S1 | **SOAP 1.1 正常调用**（Add 1+2） | `HTTP 200` `Content-Type: text/xml; charset=utf-8`；响应 `<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><AddResponse xmlns="http://tempuri.org/"><AddResult>3</AddResult></AddResponse></soap:Body></soap:Envelope>` |
| S2 | 不带 `SOAPAction` | 该服务**仍 200**（不强制）；但 WS-I BP 与多数企业服务**要求** → 必须可配 |
| S3 | **SOAP Fault**（intA 传非数字） | **`HTTP 500`** + `<soap:Body><soap:Fault><faultcode>soap:Client</faultcode><faultstring>System.Web.Services…Input string was not in a correct format…</faultstring><detail/></soap:Fault></soap:Body>` |
| S4 | **SOAP 1.2**（env ns `http://www.w3.org/2003/05/soap-envelope` + `Content-Type: application/soap+xml; action="…"`） | **同一服务也支持**：`HTTP 200` `application/soap+xml` ✅ |

### 0.3 代码事实（决定 SOAP 可行性的关键三条）

```java
// UpstreamInvoker.invoke（出站热路径）
resp = dispatch(spec);                                   // defaultStatusHandler 不抛 → 所有状态码都拿到 body
if (resp.getStatusCode().is5xxServerError()) {
    throw new HttpServerErrorException(resp.getStatusCode(), "供应商 5xx");   // ⚠️ body 被【丢弃】
}
@Retryable(includes = {HttpServerErrorException.class, TooManyRequests.class, ResourceAccessException.class}, …)
```

| # | 事实 | 后果 |
|---|---|---|
| C1 | 5xx 抛出时**不带响应体** | **`faultstring` 全程丢失** —— 死信/告警/监控都看不到供应商真实原因（与"先取证再二分"的排查纪律冲突） |
| C2 | `@Retryable(includes=…)` 含 `HttpServerErrorException` | **SOAP `Client` 类 Fault（HTTP 500，确定性错误）会被短重试 `maxRetries` 次，再无限补偿到死信** → 白耗配额与延迟 |
| C3 | `defaultStatusHandler(HttpStatusCode::isError, 不抛)`（`RestClientConfig:64-67`） | 5xx 的 **ResponseEntity 是拿得到的**（只是当前没带上）→ 修 C1 成本低 |
| C4 | Spring 7 API（已 `javap` 验证） | `HttpServerErrorException.create(status, text, headers, body, charset)` ✅ 可带 body |
| **C5** | Spring 7 `@Retryable.includes` 的**默认值是空数组**（已 `javap` 验证），项目显式列出的 3 个类型因此构成**白名单收窄** | “不进 includes = 不重试”成立 ✅ **但有个陷阱**：若新异常**继承** `HttpServerErrorException`，会因 `instanceof` 命中白名单→ **继续重试**，设计静默失效 → 见 §5.4 ① |

> ⇒ **完整 SOAP 的第一件事不是"拼 Envelope"，而是先把 Fault 的可见性与分类修掉**（否则 SOAP 上线后
> 供应商报错永远只有一个 "供应商 5xx"，且 Client 类错误会被重试到死信）。

---

## 1. `encoding` 的真实语义（Q6 已拍板 = A）

| | **语义 A：声明可配（本期采用）** | 语义 B：原生目标编码字节（实测不可达） |
|---|---|---|
| 行为 | 声明写 `encoding="GBK"`，非 ASCII 文本以**字符引用** `&#x4e2d;` 输出，字节**保持 ASCII 安全** | 中文以 GBK 双字节 `d6d0` 输出 |
| 合规性 | ✅ 完全合法（字符引用与原生字节 XML 语义等价；ASCII ⊂ GBK，供应商按声明解码必对） | ✅ 合法 |
| 能满足 | 任何**合规解析器** + "要求声明必须是 GBK"的老系统 | 仅"对原始字节做字符串匹配"的**土解析器** |
| 实现 | **低**（`:247`+`:248`+`:259`） | 高（Woodstox 做不到，需自写 writer/后处理） |

> **收益口径**：可配 `encoding` 是为了「**让声明与供应商期望一致**」，**不是「改变字节」** ——
> 这一点必须写进 UI hint，否则会造成"以为在发 GBK 字节"的误解（风险 R2）。
> 附带好处：我们**发出的**报文永远 ASCII 安全 → 消掉了 v1.0 担心的"日志乱码"风险
> （入站 GBK 报文在 `call_log.req_body` 仍会乱码，属既有独立问题，已记入 **§13-3**）。
>
> **⚠️ 白名单口径（实测推论，v4.1 修正）**：探针 #9 显示 `UTF-16` 会产出**原生字节**（含 BOM 与 NUL）——
> 那会打破"字节 ASCII 安全"这个前提，并使 `call_log`（固定 UTF-8 解码）与纯文本 diff 全部失真。
> 所以 `encoding` 的值域必须是「**JDK 支持 且 ASCII 兼容**」的字符集，并**显式拒绝 `UTF-16` / `UTF-32`**（见 §7.3）。

---

## 2. 目标与非目标

| | 内容 |
|---|---|
| **目标** | ① 接口级 `protocol_params`：`xml.version` / `xml.encoding` / `xml.root` / `xml.namespace{prefix,uri}` ② **命名空间真正生效**（默认 + 带前缀两种）③ **根元素可配** ④ **完整 SOAP**（1.1 / 1.2：Envelope/Header/Body 包裹、`SOAPAction`/`action`、Content-Type、**响应解包**、**Fault 可见 + 分类**）⑤ ack 与出站**同源** ⑥ 非法值 `40001` ⑦ 撤下旧死参数 `attrVsElement` ⑧ 参数随快照版本化（回滚可回滚） |
| **非目标** | ① 原生 GBK 字节（§1 语义 B）② SOAP **自定义 Header 块**（无真实样本前不猜；本期只"不写 Header"）③ SOAP **1.2 的 HTTP 200 + Fault**（罕见；本期覆盖"Fault 伴随 5xx"）④ `ack` 的 SOAP 化 ⑤ 解码侧 version/encoding 校验（**有意宽松**）⑥ `standalone` / `omitDeclaration` / 引号风格（P2）⑦ 应用级默认层（Q7/Q14 结论：**不做**）⑧ **入站方向的 SOAP 解包**（**Q17=是**：`soap` 仅用于出站构造 + 响应解包；"平台对外暴露 SOAP 端点"见 §13.1 #11） |

> **实施范围（Q19）**：上述目标中的 **④ 完整 SOAP（B2）延后实施**，待真实 SOAP 供应商样本；
> **① ② ③ ⑤ ⑥ ⑦ ⑧ = B1，立即实施**（7.4 人日）。设计内容全部保留（B2 开工时直接可用）。

---

## 3. 决策记录

### 3.1 载体与 XML 基础（v2.0 生效，摘要）

| # | 决策 |
|---|---|
| D-XD-1 | 载体 = **`interface.protocol_params`（接口级 JSON）**（语义：`protocol_in/out` 本就是接口属性；M0-01 D5 明确协议"不参与绑定"） |
| D-XD-2 | 缺省链：接口级 → **内置默认**（`1.0`/`UTF-8`/`request`/无命名空间）= 硬编码前行为（零回归） |
| D-XD-3 | 白名单 + **非法值 `40001`**（不吞成 `50000`） |
| D-XD-4 | `encoding` = **语义 A**，UI hint 明示"非 ASCII 以字符引用输出" |
| D-XD-5 | 命名空间用**标准三参 API** |
| D-XD-7 | 装配期**烘焙进链缓存** + `INTERFACE` 事件失效（复用既有机制，零新增） |
| D-XD-8 | 参数进 `interface_snapshot.main`（**七段结构不变**，只多一键） |
| D-XD-9 | **撤下** `attrVsElement`（元数据 + 实现路径），记 backlog |
| D-XD-10 | 解码侧**不校验** version/encoding |

### 3.2 SOAP（v3.0 新增）

| # | 决策 | 理由 |
|---|---|---|
| **D-SOAP-1** | SOAP 实现在 **`XmlProtocolAdapter`（协议适配器）**，**不用 MESSAGE 角色** | 硬约束三条：① **带前缀的命名空间在模型层表达不了**（模型元素名只是字符串，三参 API 才正确）；② `Content-Type` 由 ENCODE 设置且 **MESSAGE 阶段在其之前**（MESSAGE 设的会被覆写）；③ `SOAPAction` 是协议层头，与鉴权头同层。§5 有更详论证。<br>**评审必问：“那 `EnvelopeMessageAdapter`（MESSAGE 角色）为何可以？”** —— 因为两者层次不同：<br>· `EnvelopeMessageAdapter` = **模型层的信封**（`data` / `code` / `message` 都是**普通字段名**，模型完全能表达）→ 自然适合 MESSAGE 角色；<br>· SOAP 骨架 = **协议层的骨架**（`xmlns` / 前缀绑定 / Envelope–Body 包裹 / `Content-Type` / `SOAPAction`，模型层**均无法表达**，也不是字段级语义）→ 必须在协议适配器。<br>一句话：**字段级信封能做到的事就不上协议层；SOAP 超出了字段级。** |
| **D-SOAP-2** | `soap.version ∈ {1.1, 1.2}`，两套线协议（envelope ns / Content-Type / action 位置 / Fault 结构） | 实测 S1/S4 两者都真实可用；1.1 覆盖绝大多数（尤其老 .NET/Java Axis），1.2 覆盖现代服务 |
| **D-SOAP-3** | 响应**解包默认开启**：`Envelope.Body.<firstChild>` → 业务根；**宽容**（非 Envelope → 原样 + `log.warn`） | 不解包则业务数据被埋两层（`{Envelope:{Body:{…}}}`），RESP 白名单与字段映射都要写 `Envelope.Body.X` 路径 —— 反直觉且易错 |
| **D-SOAP-4** | **Fault 分类**：`faultcode` ∈ {`Client`, `Sender`} → **死信、不重试、不计熔断失败**；∈ {`Server`, `Receiver`} → 维持 5xx 语义（短重试 + 补偿 + 计熔断失败） | 实测 S3：Fault 是 **HTTP 500**，而 Client 类错误是**确定性的**（我们请求错了），重试与补偿纯属浪费；反过来若计入熔断失败，**会把供应商误判为不健康并触发熔断，影响所有共用该接口的调用** |
| **D-SOAP-5** | 为此改 `UpstreamInvoker` 的 5xx 分支：**带 body 抛**（`HttpServerErrorException.create(…)`，C4 已验证）；并新增 `SoapClientFaultException`（**不在 `@Retryable` includes** → 天然不重试） | 修 C1（faultstring 可见）+ C2（Client 类不重试）。`SoapClientFaultException` 不进 includes 是利用既有重试机制的最小手段 |
| **D-SOAP-6** | **ack 不 SOAP 化** | ack 是平台回给**供应商回调**的回执，供应商回调普遍是普通 HTTP POST + XML（设计 §5.5 已定 ack 渲染随 `protocol_in`）。若某 SOAP 供应商要求 SOAP 化 ack → 单独立项 |
| **D-SOAP-7** | **入站请求方向【不解包】；解包只做响应方向**（**Q17 已拍板 = 是**，替代原"入站宽容解包"） | 原设计让入站也宽容解包，但 `soap` 配置与响应解码**共用** ⇒ SOAP-out 接口的**每次**入站调用（调用方发的是普通 XML）都会命中"解包失败 → `log.warn`" ⇒ **日志刷屏**；而 `protocol_in` 是**平台自家对外契约**，不必兼容 SOAP。→ 解包仅作用于**响应方向**（此时报文明说对方是 SOAP，解包失败是**真信号**，warn 恰当） |
| **D-SOAP-8** | SOAP Header **本期不写**（不输出 `<soap:Header>`） | 无真实样本前不猜结构；需要时再加 `soap.header` 模板（backlog） |
| **D-SOAP-9** | **1.2 的"HTTP 200 + Fault"** 不在本期 | 规范允许（SHOULD 500 非 MUST），但罕见；本期覆盖"Fault 伴随 5xx"。补它需改 `ResponseJudger` 契约（**被前置编排共用**）→ 成本不成比例，已记入 **§13-2** |

---

## 4. 参数定义（`protocol_params` 全量）

```jsonc
// ALTER TABLE interface ADD COLUMN protocol_params LONGTEXT NULL
//   COMMENT '协议参数 JSON（XML 声明/根元素/命名空间/SOAP；空 = 平台内置默认）' AFTER protocol_out;
{
  "xml": {
    "version":  "1.0",                        // 1.0 | 1.1                    （默认 1.0）
    "encoding": "UTF-8",                      // 声明用字符集，须「JDK 支持 且 ASCII 兼容」（默认 UTF-8）
                                              //   典型：UTF-8 / GBK / GB2312 / GB18030 / Big5 / Shift_JIS / ISO-8859-1
                                              //   拒绝：UTF-16 / UTF-32（非 ASCII 兼容 → 破坏"字节 ASCII 安全"前提，见 §1）
    "root":     "QueryRequest",                // 业务元素名（默认 request）
                                               //   非 SOAP = 文档根元素
                                               //   SOAP    = <Body> 内第一个子元素（业务元素）
    "namespace": { "prefix": "ns", "uri": "http://example.com/svc" },  // 可选
                                               //   作用于业务元素；prefix 空/省略 = 默认命名空间
    "soap": {                                  // 出现即启用 SOAP 包裹
      "version": "1.1",                        // 1.1 | 1.2（默认 1.1）
      "action":  "http://tempuri.org/Add",     // 1.1 → SOAPAction 头；1.2 → Content-Type 的 action=
      "envelopePrefix": "soap",                // envelope 前缀（默认 soap；1.2 常用 env）
      "unwrapResponse": true                   // 响应解包（默认 true；false = 保留 Envelope 层级）
    }
  }
}
```

**设计要点**：
- 复用 `root`/`namespace` 描述**业务元素**（非 SOAP = 文档根；SOAP = Body 内子元素）→ **不新增重复字段**；
- 嵌套 `xml` / `soap`：为将来 `{"json":{…}}` / `{"ack":{…}}` 留位，避免再加列；
- `envelopePrefix` 可配是因为不同服务端示例/工具链期望不同（`soap` / `env` / `soapenv`）——**前缀本身无语义**（绑定的是 namespace URI），但改它能让日志/联调对照更顺眼。

**校验口径（v4.1 补，防"配错了但看起来生效"）**：

| 情形 | 处置 | 理由 |
|---|---|---|
| **未知键**（如拼错 `rootEelement`） | **`40001`**（**不是忽略**） | 忽略会让拼写错误**静默回落默认**，正是 §7.4 ② 要消灭的失效形态 |
| JSON 协议的接口配了 `xml` 段 | **`40001`** | 用错方向的配置应**立刻报错**，而非静默无效 |
| `root` 为空串 | **`40001`** | 本期**不支持"Body 直放字段"**（SOAP 下 Body 内不额外加业务包裹元素）；需要时另立 |
| `xml` 存在但为 `null` / `{}` | 视为"未配置"→ 内置默认 | 允许清空配置（回退手段，§11） |
| `encoding` 非「JDK 支持 且 ASCII 兼容」 | **`40001`**（**显式拒绝 `UTF-16`/`UTF-32`**） | 见 §1 白名单口径 |

---

## 5. SOAP 线协议设计

### 5.1 请求构造（1.1 / 1.2 对照，均经实测 S1/S4 验证）

| 项 | SOAP 1.1 | SOAP 1.2 |
|---|---|---|
| Envelope 命名空间 | `http://schemas.xmlsoap.org/soap/envelope/` | `http://www.w3.org/2003/05/soap-envelope` |
| `Content-Type` | `text/xml; charset=<enc>` | `application/soap+xml; charset=<enc>; action="<action>"` |
| action 传递 | **独立头** `SOAPAction: "<action>"` | **在 Content-Type 内**（无 SOAPAction 头） |
| Header 块 | 本期不写（D-SOAP-8） | 同 |

**产出样例（1.1，`root=Add`、业务命名空间 `http://tempuri.org/`）**：

```xml
<?xml version='1.0' encoding='UTF-8'?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <Add xmlns="http://tempuri.org/"><intA>1</intA><intB>2</intB></Add>
  </soap:Body>
</soap:Envelope>
```
> 与实测 S1 的请求形状一致（实际服务返回 `<AddResult>3</AddResult>`）。
> 实现用三参 API（探针 #2/#3）：`writeStartElement(effPrefix, "Envelope", envNs)` + `writeNamespace`，
> `Body` 同法，业务元素按 `namespace` 决定用默认命名空间还是前缀命名空间。

### 5.2 响应解包（D-SOAP-3）

```
供应商响应（实测 S1）
  <soap:Envelope><soap:Body><AddResponse xmlns="http://tempuri.org/"><AddResult>3</AddResult></AddResponse></soap:Body></soap:Envelope>
        ↓ XmlProtocolAdapter.decode（SOAP 宽容解包）
  业务模型 = { AddResult: 3 }        ← 业务根 = Body 内第一个元素，进入 RESP 白名单 / 字段映射
```
- 判据：根元素 localName == `Envelope` **且**存在 `Body` 子元素 → 解包；否则原样返回 + `log.warn`（宽容，避免把非 SOAP 报文判死）；
- ⚠️ **仅用于响应方向**（**Q17=是**）：入站请求（`ChainEngine:235` DECODE）**不解包** —— 否则 SOAP-out 接口的每次入站调用都会 `log.warn` 刷屏（见 D-SOAP-7）。
  响应方向的 warn 是**真信号**（接口配了 SOAP 就说明对方应答是 SOAP，解不出来就是配置与供应商不符）；
- 解包后 **`namespace` 前缀按既有规则剥离**（现有 `readElement` 取 localName），所以 `AddResponse` / `ns:QueryResponse` 都能被映射直接引用；
- 响应方向的 `Content-Type` 不作为判据（部分服务返回 `text/xml` 而非 `application/soap+xml`）。

### 5.3 Fault（实测 S3 + 1.2 规范差异）

| 项 | SOAP 1.1（实测） | SOAP 1.2 |
|---|---|---|
| 定位 | `Envelope.Body.Fault` | 同 |
| 代码字段 | `faultcode` = `soap:Client` / `soap:Server` | `Code.Value` = `env:Sender` / `env:Receiver` |
| 消息字段 | `faultstring` | `Reason.Text` |
| 详情 | `detail` | `Detail` |
| HTTP 状态 | **500**（实测） | 通常 500 |

**分类（D-SOAP-4）**：

| faultcode | 语义 | 终态 | 错误码 | 短重试 | 补偿 | 熔断计数 |
|---|---|---|---|---|---|---|
| `Client` / `Sender` | **我们请求错了**（参数/格式/权限） | **DEAD_LETTER** | **`50203`（新增）** | ❌ 不重试 | ❌ | ❌ **不计失败** |
| `Server` / `Receiver` | 供应商服务端故障 | COMPENSATING | `50201` | ✅ | ✅ | ✅ 计失败 |
| 无 Fault 的纯 5xx | 同现状 | COMPENSATING | `50201` | ✅ | ✅ | ✅ 计失败 |

> 新增错误码 **`50203` SOAP Fault（客户端错误）** → 需登记到设计总纲 §6.2 错误码表（502xx 段）。

### 5.4 链路改动点（Fault 路径）——**含 4 条必须照抄的实现约束**

> v4.1 重要修正：完整 SOAP 的难点**不在"拼 Envelope"**，而在 Fault 的可见性与分类。
> 下面 ①②③ 三条是**审查中发现的真陷阱**，不照抄必出错。

#### ① `SoapClientFaultException` **必须直接继承 `RuntimeException`**

```java
// ✅ 正确
public class SoapClientFaultException extends RuntimeException { … }

// ❌ 错误：会因 instanceof 命中 @Retryable(includes = {… HttpServerErrorException …}) → 继续短重试
public class SoapClientFaultException extends HttpServerErrorException { … }
```
理由：`includes` 默认空数组（实测 C5）→ 项目列出的 3 个类型构成**白名单**；但白名单匹配是
`instanceof` 语义，继承就会命中。→ **用例 19 专钉**（WireMock 计数 = 1 且状态链"短重试次数" = 0）。

#### ② `OutboundEngine` 的 catch 形状：**单条 `catch (Exception)` + 内部 instanceof 链**

```java
// 真实形状（OutboundEngine.doInvoke）——新分支必须加在 instanceof 链【最前面】
} catch (Exception e) {
    if (e instanceof SoapClientFaultException sf) {          // ← 新增（放最前）
        throw classifySoapClientFault(recordId, sf, trigger, how, attempt);   // 死信 + 50203
    }
    if (e instanceof ResourceAccessException
            || e instanceof HttpClientErrorException.TooManyRequests
            || e instanceof HttpServerErrorException) {
        circuitBreakerRegistry.record(iface.id(), false);     // ← 新异常【不进】这里 ⇒ 熔断不计失败（自动成立）
        …
    }
    throw e;   // ⚠️ 若不加上面的分支：新异常会落这里 → 冒到 execute 的 catch(Exception) → BizException(50000)「平台内部错误」
}
```

#### ③ `PreStepExecutor` 必须显式处理同一异常（**前置编排是 v4.1 新发现的漏点**）

前置的 catch 链是 `ResourceAccessException` / `HttpServerErrorException|429` / `catch (Exception e)`——
新异常会落进**最后一条**，被归为 `PreStepFailure.Kind.CONFIG_ERROR` / **`40001`「调用异常」**
（**语义完全错**，且宿主按"链失败"而非"上游拒绝"处理）。必须新增一条：

```java
} catch (SoapClientFaultException e) {                        // ← 必须，且放在 catch (Exception e) 之前
    throw fail(step, traceId, attempt, PreStepFailure.Kind.HTTP_5XX, 50203,
               "SOAP Fault（客户端）：" + e.faultCode() + " / " + truncate(e.faultString(), 200), ms, 0);
}
```
> 宿主侧对 `PreStepFailure` 的三条出口已定稿（D-PS 系列）→ 本条**不新增出口**，只修正 `Kind` 与错误码。

#### ④ 链路总览

```
XmlProtocolAdapter.encode ── spec.soapVersion = 1.1/1.2（新：告诉 Invoker 可按 SOAP 解析 5xx）
        ↓
UpstreamInvoker.invoke
   resp = dispatch(spec)
   if 5xx:
       若 spec.soapVersion 非空 且 body 是 SOAP Fault（**结构匹配 Body/Fault**，不做字符串嗅探）:
            faultcode ∈ {Client, Sender}  → throw SoapClientFaultException(code, faultstring)   ← 见 ①
            否则                          → throw HttpServerErrorException.create(…, body …)   ← 带 body（修 C1）
       否则                              → throw HttpServerErrorException.create(…, body …)   ← 带 body（修 C1）
        ↓
OutboundEngine（见 ②）/ PreStepExecutor（见 ③）
```

#### ⑤ `faultstring` 落库的**截断与取证口径**（v4.1 补）

实测 S3 的 `faultstring` 是 **~1.5KB 的 .NET 全堆栈**，直接落库会超列宽：

| 落点 | 列/字段 | 列宽 | 口径 |
|---|---|---|---|
| `dead_letter.reason` | VARCHAR(2000) | 够 | `faultcode` + **`faultstring` 前 500 字符** + `…[截断，原长 N]` |
| `dead_letter.payload` | LONGTEXT | 够 | **落供应商原始响应体**（取证；与既有 4xx 死信口径一致） |
| `alert_event.message` | VARCHAR(500) | **紧** | 只放 `faultcode` + `faultstring` **首行**（不塞全堆栈） |
| `call_log` OUT 条 `resp_body` | LONGTEXT | — | ⚠️ 异常路径**当前恒为 `null`**（`writeOut(…, null, e)`）→ faultstring 进不了调用日志，已记 **§13-10** |

> 截断口径 **Q18 已拍板 = 按上表口径执行**（不再待定）。

---

## 6. 数据与快照

| 项 | 内容 |
|---|---|
| **DDL** | 1 列：`interface.protocol_params LONGTEXT NULL`（库已清空 → 无数据回填） |
| 代码 | `InterfaceRow`(+MAPPER) / `InterfaceRepository` 列清单 / `InterfaceRequest`+`Response`（**沿用 `steps` 的 compat 构造器先例 → 既有调用点零改动**） |
| **快照** | `main` 增 `protocolParams` 键（**七段不变**，D-XD-8）→ **回滚能回滚协议参数**（B 方案核心收益） |
| **快照存法**（v4.1 补） | `main` 里存**解析后的 JsonNode 对象**，**不是**原始 JSON 字符串 —— 存字符串会**双重转义**（`"{\"xml\":…}"`），使 `SnapshotChangeDiff` 的版本变更详情不可读 |
| 变更说明 | `SnapshotChangeDiff` 加"协议参数"项（否则改了看不见） |
| **复制** | `InterfaceService.copy()` 带上 `protocol_params`（**易漏点**） |

---

## 7. 运行时设计

### 7.1 调用点（**4 处，各自读什么不同** —— 最易出错处）

| # | 调用点 | version/encoding | root/namespace | soap |
|---|---|---|---|---|
| 1 | `ChainEngine:235` DECODE（入站请求） | ❌ | ❌ | ❌ **不读**（**Q17=是**：入站**不解包**；`soap` 只作用于【出站构造】与【响应解包】。⚠️ 此处必须加注释说明"不读是有意的"，防后人误以为漏了） |
| 2 | `ChainEngine:265` ENCODE（出站请求） | ✅ | ✅ | ✅ **全读**（包裹 + Content-Type + SOAPAction + `spec.soapVersion`） |
| 3 | `ChainEngine:316` `decodeResponse` | ❌ | ❌ | ✅ **只用于解包**（⚠️ **此处当前完全不传 `adapterParams`，需新增传参**） |
| 4 | `AckRenderer:86`（入站回调 ack） | ✅ | ❌（根元素维持 `response` 约定，D-XD-6） | ❌ **不 SOAP 化**（D-SOAP-6） |

> 第 3 处是本方案新发现的缺口：`decodeResponse` 自建 ctx 且**从未注入 `adapterParams`** →
> SOAP 响应解包要生效，必须在此补传（同时解释为什么第 1/3 处"只解包、不编码"）。

### 7.2 装配 / 缓存 / 失效

- 装配期一次解析并**烘焙进链缓存**（D-XD-7）；
- `InterfaceService` 的 update/rollback/publish/offline/delete **已发 `INTERFACE` 事件** → 零新增失效机制。

### 7.3 失败语义（保存期 + 运行期）

| 场景 | 处置 |
|---|---|
| `version=1.2`（非法） | 保存期 `40001`；运行期兜底 `40001` |
| `soap.version` 非 `1.1/1.2` | `40001` |
| `root` 非合法 XML 名（含空格/`<`/`:`） | `40001`（前缀只由 `namespace.prefix` 表达） |
| `namespace.uri` 非法 | `40001`（`URI.create` 校验，防运行期 500） |
| `encoding` 非「JDK 支持 且 ASCII 兼容」 | `40001`（**显式拒绝 `UTF-16`/`UTF-32`**：会产原生非 ASCII 字节，破坏 §1 的“字节 ASCII 安全”前提） |
| `protocol_params` 含**未知键**（拼错 `rootEelement`） | `40001`（不忽略 —— 否则静默回落默认，即 §7.4 ② 要消灭的失效形态） |
| JSON 协议的接口配了 `xml` 段 | `40001`（配错方向应立刻报错，而非静默无效） |
| `root` 为空串 | `40001`（本期不支持“Body 直放字段”） |
| `soap.version=1.2` 但 `action` 为空 | `40001`（1.2 的 action 必须传递，无处可省） |
| 5xx 且 body 不是 SOAP Fault | 维持现状（补偿） |

### 7.4 兜底层次（三层，别混为一谈 —— Q5 结论的展开）

“兜底”一词容易把三件不同的事混在一起，实现时必须分开：

| 层次 | 机制 | 位置 | 本期 | 为何这样定 |
|---|---|---|---|---|
| **① 默认值兜底**（自动） | 接口未配 → 用**内置默认**（`1.0`/`UTF-8`/`request`/无命名空间），= 硬编码前行为 | 代码内常量（`XmlProtoConfig`） | ✅ **必做** | 零回归的保证，也是可灰度/可回退的前提 |
| **② 配置校验兜底**（自动） | 配置非法 → **拒绝保存（`40001`）**，**不静默回落默认** | 服务端校验（保存期） | ✅ **必做** | 静默兜底会让"配错了但看起来生效"成为常态；而错误配置必须在**保存那一刻**就被拦住（探针 #6：漏到运行期会被吞成 `50000`） |
| **③ 应急总开关**（人工） | 一个开关让**全部接口**忽略各自 `protocol_params`、强制回内置默认 | 若要，**只能放 `application.yaml`**（与 `pre-step.enabled` / `circuit.enabled` / `auth.enabled` 同风格），**不是页面** | ❌ **本期不做**（D-XD-12） | 页面总开关易被误点导致**全平台**协议行为突变；应急场景要的是"改配置立即止血"，走配置中心 + 重启更可控 |

**为何选了 B 方案后 ③ 可以不做**：坏配置只影响**单个接口**，而接口已有**版本回滚（M5 D-M5-1）**这个一键回退手段
——且 `protocol_params` 已进快照（D-XD-8）→ **回滚能精确回退它**。因此改为：在接口详情 / 版本历史里把协议参数变更显示清楚 + 支持一键回滚。
若后续真出现"坏协议配置批量铺开、逐接口回滚太慢"，再加 `app.api-center.xml-proto.force-default=true`（0.2 人日）。

---

## 8. 影响面与风险

### 8.1 影响面（本方案与 v2.0 的差异集中在 SOAP）

| 层 | 位置 | 改动 | 风险 |
|---|---|---|---|
| **出站热路径** | `UpstreamInvoker.invoke` 5xx 分支 | 带 body 抛 + SOAP Fault 探测（**结构匹配 `Body/Fault`**，不做字符串嗅探） | **中高**：热路径；`includes`/`predicate` 重试机制敏感（CLAUDE.md 记有 SpEL 求值缓存坑） |
| **熔断语义** | `OutboundEngine` 熔断计数分支 | 由**类型天然排除**（不继承 `HttpServerErrorException` ⇒ 不进 `record(false)` 分支） | 中：若误让它继承 → **双重错**（① 继续重试 ② 计熔断失败）→ 见 §8.2 R9 |
| **引擎分类** | `OutboundEngine.doInvoke` catch | 新增 `SoapClientFaultException` 分支 → 死信 + `50203`（**必须是 instanceof 链最前面**，见 §5.4 ②） | 中 |
| **前置编排**（v4.1 新发现） | `PreStepExecutor` catch 链 | 新增 `catch (SoapClientFaultException)` → `50203`；**不新增出口** | 中：**否则会落 `catch(Exception)` → `40001 CONFIG_ERROR`，语义错**；见 §5.4 ③ |
| 指标口径（v4.1 补） | `CallLogAspect.outcomeOfOut` | `SoapClientFaultException` 归 **`upstream_fail`**（不是 `transport_fail`：它是上游明确拒绝，不是传输失败） | 低（否则与 4xx 口径不一致，成功率被污染） |
| **协议适配器** | `XmlProtocolAdapter`（encode/decode） | 包裹 / 解包 / 1.1+1.2 / Content-Type / SOAPAction | 中（**单一产出点 `:247-249` 收敛，改动集中**） |
| 响应解码传参 | `ChainEngine:316` `decodeResponse` | 补传 `adapterParams` | 低（此前无 SOAP 时无影响） |
| **`ResponseJudger`** | —— | **本期不动**（D-SOAP-9 把"200+Fault"排除在外） | ✅ 规避了与**前置编排**共用实现的耦合风险 |
| 快照 | `SnapshotSerializer` / `SnapshotChangeDiff` | `main` +1 键（**解析后的 JsonNode**）+ 变更项 | 低 |
| 前端 | `Interfaces.vue` + `utils/` | 协议参数区（含 SOAP 子表单，条件显示；`root` 标签动态切换） | 低 |
| 文档 | 设计总纲 / `表结构设计.html` / `使用教程` / `整体测试方案` / `CLAUDE.md` | 见 §9 清单 #13（v4.1 补漏的文档同步） | 低 |

**测试影响**：既有 290 测试**零回归**（未配置 = 内置默认 = 现状；`InterfaceRequest` compat 构造器保住既有调用点）。
唯一需要复核的是 **5xx 分类相关用例**（M2/M4 的 5xx → COMPENSATING）——它们断言的是"非 SOAP 5xx"，改造后行为不变，但**必须在改造后跑全量确认**。

### 8.2 风险

| # | 风险 | 等级 | 处置 |
|---|---|---|---|
| R1 | 改 `UpstreamInvoker` 热路径引发重试/熔断回归 | **中高** | ① 改造**不改变**非 SOAP 5xx 行为（带 body 抛 ≠ 换异常类型）；② 全量回归 + 专项用例；③ 灰度 S0b 先上"仅带 body 不改分类" |
| R2 | `SoapClientFaultException` 被计入熔断失败 → 误熔断 | 中高 | **由类型继承关系天然保证**（见 R9）+ 用例（连续 Client Fault 后熔断状态**仍为 CLOSED**） |
| **R9**（v4.1） | **`SoapClientFaultException` 误继承 `HttpServerErrorException`** → ① `instanceof` 命中白名单**继续重试** ② 计入熔断失败 —— **"不重试"设计静默失效** | **中高** | §5.4 ① 钉死继承关系 + **用例 19**（WireMock 计数 = 1 且状态链短重试次数 = 0） |
| **R10**（v4.1） | **`PreStepExecutor` 漏处理** → 前置目标返回 Client Fault 被归为 `40001 CONFIG_ERROR` | 中高 | §5.4 ③ + **用例 23** |
| **R11**（v4.1） | **配置未知键被忽略** → 拼错后静默回落默认（"配错了但看起来生效"） | 中 | §4 校验口径（未知键 `40001`）+ **用例 20** |
| **R12**（v4.1） | SOAP 解包用于**入站方向** → 每次调用 `log.warn` 刷屏 | 中 | **待 Q17**（建议本期不解包） |
| R3 | 以为 `encoding=GBK` 会发 GBK 字节 | 中高 | §1 + UI hint + 用例断言"字节全 ASCII"（用例 3） |
| R4 | Fault 探测误判（非 SOAP 报文的 5xx 里含 `<Fault`） | 低 | 判据 = `spec.soapVersion` 非空 **且** **结构匹配** `Body/Fault`（双重条件，不做纯字符串嗅探） |
| R5 | 前缀写错（`root` 里写 `ns:QueryRequest`） | 低 | 保存期 `40001`（前缀只由 `namespace.prefix` 表达） |
| R6 | `decodeResponse` 漏传 config → SOAP 响应不解包，data 多一层 Envelope | 中 | §7.1 第 3 处标注 + 用例 8 |
| R7 | ack 被误 SOAP 化 | 中 | D-SOAP-6 明确 + 用例 12 |
| R8 | 1.2 的 action 为空 | 低 | 保存期 `40001` |
| **回退** | 清空/回滚接口 `protocol_params` → 回落内置默认（无重启、无开关） | — | — |

---

## 9. 实施清单

> **实施顺序（Q19 已拍板）**：**立即实施 = B1（7.4 人日）**；**B2（完整 SOAP，6.4 人日）延后**，
> 触发条件 = 拿到真实 SOAP 供应商的 WSDL / 请求响应样例（本项目文化是"真实接口驱动"；
> `D-SOAP-8`（不写 Header）等均是**无样本下的保守取舍** —— 无样本实现的 SOAP 容易"看着对、连不上真服务"）。
> B2 的设计内容已完整保留，开工时直接可用（§5、§8、§10.1 用例 6-10/15-19/23-25）。

### 批次 B1：XML 基础（`version` / `encoding` / `root` / `namespace`）—— ✅ **立即实施**

| # | 文件 | 改动 | 人日 |
|---|---|---|---|
| 1 | `schema.sql` + 开发库 | `interface.protocol_params` 列 | 0.2 |
| 2 | `InterfaceRow` / `InterfaceRepository` | 字段 + MAPPER + SQL 列 | 0.4 |
| 3 | `InterfaceDtos` | 请求/响应字段（沿用 `steps` compat 构造器先例） | 0.3 |
| 4 | `XmlProtoConfig`（新） | 解析 + 白名单 + NCName/URI 校验 + 默认（纯函数） | 0.6 |
| 5 | `InterfaceService` | 保存校验 + **复制带上参数** | 0.5 |
| 6 | `SnapshotSerializer` / `SnapshotChangeDiff` | `main` +1 键 / 变更项 | 0.5 |
| 7 | `ChainEngine` | 装配期解析烘焙 + `:265` 注入 + `:316` 补传 + `:235` 注释 | 0.6 |
| 8 | `XmlProtocolAdapter` | `:247-249` 读配置 + 命名空间三参 + 非法值 `40001` | 0.7 |
| 9 | `AckRenderer` | 只取 version/encoding（D-XD-6） | 0.3 |
| 10 | `AdapterImplCatalog` | 撤下 `attrVsElement` | 0.2 |
| 11 | 前端（`Interfaces.vue` + `utils/`） | 协议参数区（version/encoding/root/ns）+ 校验镜像 + hint（含 §1 语义 A 说明）；**SOAP 时 `root` 标签动态改为「Body 内业务元素」**（§10 优化 ⑨） | 1.3 |
| 12 | 测试 | §10.1 用例 1-5、11-14、**20-22** | 1.3 |
| 13 | **文档同步**（v4.1 补漏） | `表结构设计.html`（+1 列）/ `API中心使用教程`（协议参数说明 + curl）/ `整体测试方案`（A 组用例）/ `CLAUDE.md` Gotchas / 本方案回填落地状态 | 0.5 |
| | **小计** | | **≈7.4** |

### 批次 B2：完整 SOAP —— ⏸ **延后实施**（Q19，待真实样本）

| # | 文件 | 改动 | 人日 |
|---|---|---|---|
| 13 | `XmlProtoConfig` | `soap` 段解析与校验（含 1.2 action 必填） | 0.3 |
| 14 | `XmlProtocolAdapter` | 包裹（Envelope/Body + 三参 ns）/ 解包（宽容）/ 1.1+1.2 Content-Type / SOAPAction | 1.3 |
| 15 | `OutboundRequestSpec` | 新增 `soapVersion`（供 Invoker 判 Fault） | 0.2 |
| 16 | `UpstreamInvoker` | 5xx **带 body 抛** + SOAP Fault 探测（含 `SoapClientFaultException`，**直接继承 `RuntimeException`**）+ faultstring 截断口径（§5.4 ⑤） | 1.0 |
| 17 | `OutboundEngine` | instanceof 链**最前面**加 `SoapClientFaultException` 分支 → 死信 + `50203`（**不计熔断由类型自然保证**，见 §5.4 ②） | 0.8 |
| 18 | **`PreStepExecutor`**（v4.1 补漏） | 新增 `catch (SoapClientFaultException)` → `50203`（见 §5.4 ③） | 0.3 |
| 19 | `CallLogAspect`（v4.1 补漏） | `outcomeOfOut` 把该异常归 **`upstream_fail`**（见 §8.1） | 0.2 |
| 20 | 错误码文档 | 设计总纲 §6.2 加 `50203` | 0.1 |
| 21 | 前端 | SOAP 子表单（version/action/prefix/unwrap，仅 XML 且勾选 SOAP 时显示） | 0.6 |
| 22 | 测试 | §10.1 用例 6-10、15-19、23-25（含真实 SOAP 服务联调） | 1.6 |
| | **小计** | | **≈6.4** |

**合计 ≈ 13.8 人日 = B1 7.4（✅ 立即）+ B2 6.4（⏸ 延后）**：
B1 比 v4.0 的 6.8 多 **0.6**（文档同步 +0.5、新增测试 +0.1）；B2 比 5.8 多 **0.6**（前置编排 +0.3、指标口径 +0.2、新增测试 +0.1）。
**B1 可独立交付上线**（SOAP 不影响其正确性）。

---

## 10. 测试方案

### 10.1 自动化用例

| # | 批次 | 用例 | 断言 |
|---|---|---|---|
| 1 | B1 | 未配 `protocol_params` | 声明/根元素**与改造前逐字节一致**（钉零回归） |
| 2 | B1 | `version=1.1` | 声明为 1.1 |
| 3 | B1 | `encoding=GBK` | 声明 `GBK`；**字节全 ASCII**；中文以 `&#x…;`（钉语义 A） |
| 4 | B1 | `root=queryRequest` | 根元素为 `queryRequest`；**ack 仍为 `response`** |
| 5 | B1 | `namespace` 两种形态 | `<queryRequest xmlns="…">` 与 `<ns:queryRequest xmlns:ns="…">` |
| 6 | B2 | SOAP **1.1** 出站 | 报文含 Envelope/Body + 业务元素；`Content-Type: text/xml`；**`SOAPAction` 头存在** |
| 7 | B2 | SOAP **1.2** 出站 | env ns 为 2003/05；`Content-Type: application/soap+xml; action="…"`；**无 SOAPAction 头** |
| 8 | B2 | SOAP 响应解包 | 供应商返回 Envelope → `data` 直接是业务字段（**不含 Envelope 层级**） |
| 9 | B2 | **Client Fault → 死信不重试** | `outbound_request` 终态 `DEAD_LETTER`、码 `50203`、**WireMock 请求计数 = 1**（不重试）+ **熔断状态仍 CLOSED** |
| 10 | B2 | **Server Fault → 补偿** | 终态 `COMPENSATING`、码 `50201`、熔断计失败 |
| 11 | B1 | 非法值（1.2 / 非法 root / 非法 uri / 1.2 无 action） | 保存期 `40001`；**不得** `50000` |
| 12 | B1 | ack 同源 | ack 声明 = 出站声明；根元素 `response`、**无 SOAP 包裹** |
| 13 | B1 | **快照往返 + 回滚** | 改协议参数 → 新版本 → **回滚后参数一并回退** |
| 14 | B1 | 接口复制 | 复制品带上 `protocol_params` |
| 15 | B2 | 非 SOAP 5xx 行为不变（回归） | `COMPENSATING` + 短重试（**证明 B2 没改坏既有语义**） |
| 16 | B2 | **5xx 的 faultstring 可见** | 死信 `reason` 含供应商原始 `faultstring`（修 C1） |
| 17 | 反证 | 去掉配置读取 | 用例 2-8 必红 |
| 18 | 反证 | 去掉熔断排除 | 用例 9 的"熔断仍 CLOSED"必红 |
| **19** | B2 | **`SoapClientFaultException` 继承关系契约**（v4.1） | 断言它**不是** `HttpServerErrorException` 子类 + WireMock 计数 = 1 + 状态链"短重试次数" = 0（防"白名单命中 → 悄悄重试"） |
| **20** | B1 | **配置未知键**（`{"xml":{"rootEelement":"x"}}`） | 保存期 `40001`；**不得**静默回落默认（钉 R11） |
| **21** | B1 | `encoding=UTF-16` | 保存期 `40001`（非 ASCII 兼容，会破字节前提） |
| **22** | B1 | JSON 接口配 `xml` 段 / `root` 为空串 | 均为 `40001` |
| **23** | B2 | **前置步骤目标是 SOAP 接口且返回 Client Fault** | 失败分类为 SOAP 客户端错误 + `50203`，**不得**是 `40001 CONFIG_ERROR`（钉 §5.4 ③） |
| **24** | B2 | **faultstring 截断与取证**（输入用实测 S3 的 ~1.5KB 堆栈） | `dead_letter.reason` 长度 ≤ 2000 且含 `…[截断，原长 N]`；`dead_letter.payload` = 供应商原始响应体 |
| **25** | B2 | `50203` 的**指标结局** | `apicenter.gateway.requests{outcome=upstream_fail}`（**不是** `transport_fail`） |

### 10.2 真实联调资产（**免密钥，公网**）

| 用途 | 端点 | 实测 |
|---|---|---|
| SOAP 1.1 / 1.2 正常路径 | `POST http://www.dneonline.com/calculator.asmx`（Add/Subtract…） | ✅ S1/S4 |
| SOAP Fault 路径 | 同端点，`intA` 传非数字 | ✅ S3（`soap:Client` + HTTP 500） |

配置：应用 base_url `http://www.dneonline.com`；接口 `POST /calculator.asmx`；`root=Add`；
`namespace={prefix:"",uri:"http://tempuri.org/"}`；`soap.version=1.1`、`action=http://tempuri.org/Add`；
入站参数 `intA`/`intB`（number）；RESP 声明 `AddResult`(number)（解包后直接可见）。
> 该服务**不要求 SOAPAction**（S2），可顺带验证"action 可空"的宽容度。

#### 直连对照 curl（v4.1 补：**二分基线**——平台失败时先用它证明供应商侧是否正常）

```bash
# ① 直连正常路径（期望 <AddResult>3</AddResult>）——与平台内成功用例对照
curl -s -X POST http://www.dneonline.com/calculator.asmx \
  -H 'Content-Type: text/xml; charset=utf-8' \
  -H 'SOAPAction: "http://tempuri.org/Add"' \
  -d '<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><Add xmlns="http://tempuri.org/"><intA>1</intA><intB>2</intB></Add></soap:Body></soap:Envelope>'

# ② 直连 Fault 路径（期望 HTTP 500 + faultcode=soap:Client）——与平台内用例 9 对照
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://www.dneonline.com/calculator.asmx \
  -H 'Content-Type: text/xml; charset=utf-8' -H 'SOAPAction: "http://tempuri.org/Add"' \
  -d '<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><Add xmlns="http://tempuri.org/"><intA>abc</intA><intB>2</intB></Add></soap:Body></soap:Envelope>'

# ③ 平台内调用（带 protocol_params 建接口后）
curl -s -X POST http://localhost:8080/pm/soap-add -H 'Content-Type: application/json' \
  -d '{"intA":1,"intB":2}'
```

#### 带 `protocol_params` 建接口（v4.1 补）

```bash
curl -s -X POST http://localhost:8080/api/admin/interfaces \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "code":"PM-SOAP-ADD","name":"SOAP 加法（dneonline）","ifType":"OUTBOUND","method":"POST",
    "path":"/pm/soap-add","protocolIn":"JSON","protocolOut":"XML",
    "appId":"DNESOAP","groupId":<groupId>,"upstreamPath":"/calculator.asmx",
    "status":"DRAFT","timeoutMs":15000,"maxRetries":1,"version":1.0,
    "protocolParams":"{\"xml\":{\"root\":\"Add\",\"namespace\":{\"prefix\":\"\",\"uri\":\"http://tempuri.org/\"},\"soap\":{\"version\":\"1.1\",\"action\":\"http://tempuri.org/Add\"}}}",
    "params":[{"side":"IN","name":"intA","type":"number","required":true,"sortOrder":0},
              {"side":"IN","name":"intB","type":"number","required":true,"sortOrder":1},
              {"side":"OUT","name":"intA","type":"number","sortOrder":0},
              {"side":"OUT","name":"intB","type":"number","sortOrder":1}],
    "mappings":[{"source":"intA","op":"rename","target":"intA","nullStrategy":"KEEP","sortOrder":0},
                {"source":"intB","op":"rename","target":"intB","nullStrategy":"KEEP","sortOrder":1}],
    "fieldDefs":[{"kind":"RESP","name":"AddResult","type":"number","sortOrder":0}],
    "bindings":[],"bodies":[],"steps":[]}'
```

### 10.3 隔离纪律

新 `@SpringBootTest` 类：四属性置 1h（`retry/alert` × `fixed-delay/initial-delay`）+ `app.api-center.auth.enabled=false`
（若直连管理面）。SOAP 用例涉及重试/熔断 → **尤其**要隔离 worker（否则与 `scan()` 抢跑）。

---

## 11. 灰度

> **批次归属（Q19）**：**S0a / S1 / S2 = B1（✅ 立即）**；**S0b / S3 / S4 = B2（⏸ 延后）**。

| 步骤（含批次） | 操作 | 通过标准 |
|---|---|---|
| **S0a**（B1，不改行为） | 上线列 + 解析 + 编码改动，全库 `protocol_params` 为空 | 290 测试全绿；报文逐字节一致（用例 1） |
| ⏸ **S0b**（B2 前置，不改分类） | 只上"5xx 带 body 抛"（修 C1），**不改** Fault 分类 | 非 SOAP 5xx 行为不变（用例 15）；死信 reason 开始有供应商原文 |
| **S1**（B1） | 配**等价**默认值（`1.0`/`UTF-8`/`request`） | 报文仍逐字节一致 → 证明"配置被消费、链路已通" |
| **S2**（B1） | 单接口启用真实值（`version/root/namespace`） | 声明与结构按配置 |
| ⏸ **S3**（B2） | 单接口启用 SOAP（1.1 → 真实服务） | 用例 6/8/16 通过 |
| ⏸ **S4**（B2） | SOAP Fault 演练 + 版本回滚演练 | 用例 9/10/13 通过（Client 死信不重试、熔断不误触发） |

> **S0b 单独成步**很重要：它把"改热路径"与"改分类语义"分开验证，出问题能立刻二分定位。

---

## 12. 决策结论（**全部闭合**，v4.2）

### 12.0 v4.0 拍板（Q1-Q16）

| # | 议题 | **结论** | 落地位置 |
|---|---|---|---|
| Q1 | 配置载体 | **B：接口级 `interface.protocol_params`** | D-XD-1、§4/§6 |
| Q2 | `encoding` 是否本期做 | **一起实现** | D-XD-4、§1 |
| Q3 | 旧死参数 `namespace`/`attrVsElement` | **先撤下**（元数据 + 实现路径） | D-XD-9、清单 #10 |
| Q4 | 命名空间 + 自定义根元素 | **本期做** | D-XD-5、§4/§5 |
| Q5 | 全局兜底开关 | **不做**（只保留①默认值兜底 ②校验拒绝；B 方案下版本回滚已覆盖应急） | **§7.4**、D-XD-12 |
| Q6 | `encoding` 语义 | **A：声明可配**（非 ASCII 走字符引用，字节 ASCII 安全） | §1（实测 #9/#10） |
| Q7 | 应用级默认层 | **不做**（只两层：接口级 → 内置默认） | D-XD-2 |
| Q8 | SOAP 范围 | **完整 SOAP**（1.1/1.2 包裹 + 解包 + Fault 分类） | §5、D-SOAP-1~5 |
| Q9 | `omitDeclaration` / 声明双引号 | **不做**（各 1 行成本已知，无真实诉求） | 非目标 ⑥ |
| Q10 | 入站 GBK 在 `call_log` 乱码 | **另立 backlog** | **§13-3** |
| Q11 | SOAP 1.2 的 **HTTP 200 + Fault** | **暂不做**，记 backlog | **§13-2** |
| Q12 | SOAP **Header 块** | **暂不做**，记 backlog | **§13-1** |
| Q13 | `50203` 错误码 | **新增**（502xx 段，与 50201/50202 并列） | §5.3、清单 #18 |
| Q14 | 应用级默认层 | **不要** | D-XD-2 |
| Q15 | 入站 GBK 日志乱码 | **另立** | **§13-3** |
| Q16 | B1 / B2 上线方式 | **分两次**（B1 零风险独立交付；B2 单独灰度、S0b 隔离验证） | §9、§11 |

### 12.1 v4.1 自审新增 3 项（**已闭合**，2026-09-21 拍板）

| # | 议题 | **结论** | 落地位置 |
|---|---|---|---|
| **Q17** | SOAP 解包的方向 | **① 只在响应方向解包**（入站**不解包**）—— 避免 SOAP-out 接口每次入站调用都 `log.warn` 刷屏；`protocol_in` 是平台自家对外契约 | D-SOAP-7、§5.2、§7.1 第 1 行、非目标 ⑧ |
| **Q18** | `faultstring` 截断口径 | **按 §5.4 ⑤ 表口径执行**：`dead_letter.reason` = faultcode + 前 500 字符 + `…[截断，原长 N]`；`payload` = 供应商原始响应体全量；`alert_event.message` 只放首行 | §5.4 ⑤、用例 24 |
| **Q19** | SOAP 是否有真实供应商支撑 | **B2 延后**，先上 B1；触发条件 = 拿到真实 SOAP 供应商 WSDL/报文样例（建议 30 天内否则正式延后） | §9 实施顺序、§11 批次归属、§13.1 #11 |

> Q13 的落地注意：`50203` 需同步《API中心设计方案.md》§6.2 错误码表 502xx 段；
> 建议语义栏写「**SOAP Fault（客户端错误，不重试）**」，与 `50201`（供应商 5xx/重试耗尽转补偿）
> 与 `50202`（熔断短路）三者语义互斥、不重叠。

---

## 13. 后续考虑（backlog，本期不做）

> **记 backlog 的目的不是"以后再说"，而是留下"什么条件下该重新拿出来"的判据**；
> 触发条件不成立就不做（避免被当成待办清单无限膨胀）。

| # | 事项 | 本期不做的理由 | **重新考虑的触发条件** | 预估成本 |
|---|---|---|---|---|
| **1** | **SOAP Header 块**（`soap:Header` 自定义内容） | 当前**无任何真实供应商样本**；盲猜结构 = 大概率做错。做它需"Header 模板"能力（模板语法 / 变量注入 / 安全边界），与 SOAP 骨架同级课题 | 出现真实 SOAP 供应商明确要求 Header（如 WS-Security 认证、版本路由、消息 ID） | +2~3 人日（含模板能力设计） |
| **2** | **SOAP 1.2 的 HTTP 200 + Fault** | 规范允许（SHOULD 500 非 MUST）但**罕见**；补它必须改 `ResponseJudger` 契约，而该实现**被前置编排（`PreStepExecutor`）共用**（D-PS 系列定稿的「唯一判定实现」）→ 为罕见情形动共用件不划算 | 真实遇到某个 1.2 供应商在 HTTP 200 下返回 `<env:Fault>`（表现为"成功但 data 含 Fault 字段"） | +1.5 人日（**须连带回归前置编排**） |
| **3** | **入站非 UTF-8 报文在 `call_log.req_body` 乱码** | 与本次特性**无关**（我们**发出**的报文恒 ASCII 安全）；属既有的"日志解码固定 UTF-8"问题（`SensitiveDataMasker:62`） | 出现 GBK/GB2312 回调供应商，且监控里看不清回调原文 | +0.5 人日（按 Content-Type charset / XML 声明解码 + 单测） |
| **4** | **原生目标编码字节输出**（encoding 语义 B） | Woodstox 实测做不到（探针 #9/#10，连专有重载也转义）；需自写 `XmlWriter` 或后处理替换字符引用 | 出现"对原始字节做字符串匹配"的供应商（且拒绝字符引用形式） | +3~5 人日 + 真机验证 |
| **5** | `attrVsElement`（属性↔元素口径） | Q3 已撤下；当前 JSON→XML 无法"造"属性（映射无 `@attr` 语法） | 需要把某个字段写成 XML **属性**（而非子元素）的供应商 | +1.5 人日（需同时扩映射语法） |
| **6** | `omitDeclaration` / 声明双引号 | 探针 #7/#8 证明各 1 行可实现，但无真实诉求 | 老解析器不接受 XML 声明，或要求双引号引号风格 | 各 0.2 人日 |
| **7** | 应用级默认层（接口 → **应用** → 内置） | Q7/Q14：两层已够；接口复制（清单 #6）可低成本批量配置 | 一个供应商有几十个接口都要同一套 XML/SOAP 参数（逐接口配变成负担） | +0.5 人日（结构与接口级同形，只多一列与一层解析） |
| **8** | **协议矩阵遗留 F-3**：响应解码失败时静默返回 `code=0 + data={}` | 属既有行为（解码宽松）；SOAP 解包同样采取"宽容 + warn"（D-SOAP-3）→ **同一失效形态** | 真实发生"配错协议/解包失败但看起来成功"导致的误判 | +0.5 人日（把链上 `warnings` 暴露到信封 msg / `data._warnings`）→ **建议与 SOAP 解包一起评估**，见下 |

> **关于 backlog #8**：D-SOAP-3 的"宽容解包"在有收益（不把非 SOAP 报文判死）的同时，也继承了 F-3 的隐患
> ——**解包失败时调用方拿到的是空数据而不是错误**。本期接受该取舍（与既有宽容策略一致），
> 但若后续做 #8，**SOAP 解包失败也应一并纳入 warnings 暴露**，两件事应同批做。

### 13.1 v4.1 新增 backlog（自审摘出）

| # | 事项 | 本期不做的理由 | **重新考虑的触发条件** | 预估成本 |
|---|---|---|---|---|
| **9** | **出站报文模板能力**（现已核实 `interface_body.raw` 的 **OUT 侧运行时完全未被读取** —— `findBodies()` 只在 `InterfaceService` 调用，引擎侧零引用 → **该配置本身是死配置**） | 本期用"参数化"方式做 SOAP 包裹（成本可控）；模板能力是**更通用**的解法，但需模板语法 / 变量注入 / 安全边界三件套 | 出现**第二种**需要自定义报文骨架的协议（第三种出现时重复实现成本已超模板能力） | +3~4 人日 |
| **10** | **异常路径的 OUT 调用日志缺 `resp_body`**：`CallLogAspect.writeOut(start, spec, status, null, e)` 在异常分支传 `null` → 5xx/超时的**供应商响应体进不了调用日志**（即使已在 `dead_letter.payload` 落了） | 既有行为（非本次引入）；故障时调用日志与死信表信息不对称 | 真实发生"只有死信表有原文、调用日志页面看不到"的排查障碍 | +0.3 人日（异常携带 body 时由切面取出落库；注意脱敏/截断与现有口径一致） |
| **11** | **平台对外暴露 SOAP 端点**（入站 SOAP 服务端能力） | Q17 建议本期入站**不解包**（`protocol_in` 是平台自家契约）；若调用方只能讲 SOAP，则需真正的服务端能力（WSDL/操作路由） | 出现"只会调 SOAP 的调用方"需求 | +3~5 人日（独立的"服务端 SOAP"课题） |

---

## 14. B1 落地记录（v4.3 · 2026-09-21）

### 14.1 实际改动（与 §9 清单对照）

| 层 | 文件 | 改动 |
|---|---|---|
| 表 | `schema.sql` + 开发库 | `interface.protocol_params LONGTEXT NULL`（已应用） |
| 模型 | `model/InterfaceRow.java` | 新增 `protocolParams`（canonical 末位）+ `MAPPER` 读取 |
| 仓储 | `repository/InterfaceRepository.java` | insert（**位置绑定序号整体下移**）+ update 列清单 |
| DTO | `dto/InterfaceDtos.java` | `InterfaceRequest` 末位新增字段 + 第三个 compat 构造器；`InterfaceResponse` 新增 |
| 解析 | `engine/XmlProtoConfig.java`（**新**） | 解析 + 白名单 + NCName/URI 校验 + 内置默认；`ATTR` 供链上下文传递 |
| 引擎 | `engine/ChainEngine.java` | 装配期解析并**烘焙进缓存链**（`Chain` 新增 `xmlConfig`）；ENCODE 注入；`decodeResponse` **补传**（原先从不注入）；DECODE 加“**入站不解包**”注释（Q17） |
| 适配器 | `adapter/protocol/XmlProtocolAdapter.java` | 读配置：声明 version/encoding（同源）+ `writeRoot`（三参 API 写命名空间）；未配时走原路径 |
| ack | `engine/AckRenderer.java` | 只取 version/encoding（根元素仍为约定 `response`） |
| 服务 | `service/InterfaceService.java` | 保存期校验（含“JSON 接口配 xml 段”）；`toRow`/`toResponse`/「**复制带上参数**」 |
| 快照 | `service/SnapshotSerializer.java` | `main` 新增 `protocolParams`（存**解析后的 JsonNode**）+ 读取兼容对象/字符串两形态 |
| 变更说明 | `service/SnapshotChangeDiff.java` | `MAIN_KEYS` 加“协议参数”；`text()` **修正对象节点返回空串**的隐性 bug |
| 元数据 | `service/AdapterImplCatalog.java` | 撤下 `namespace` / `attrVsElement`（死配置，Q3） |
| 前端 | `utils/protocolParams.mjs` + `.test.mjs`（新） | `buildProtocolParams`（**只输出非空键**；全默认返回 `null`）/ `parseProtocolParams` / 白名单常量 |
| 前端 | `views/Interfaces.vue` | 「高级」Tab 新增 XML 协议参数区（version/encoding/root/ns，仅 XML 显示）+ hint（含 §1 语义 A 说明） |
| 测试 | `engine/XmlProtoConfigTest`（**新 16**）+ `adapter/protocol/XmlProtocolAdapterTest`（+7） | 默认/未知键/白名单/NCName/URI/命名空间/字节 ASCII 安全/ack 优先 |

### 14.2 验证结果

| 项 | 结果 |
|---|---|
| 后台编译 | `mvn -o clean test-compile` ✅ |
| **全量测试** | **325 个全绿**（`mvn -o test` → BUILD SUCCESS；新增 23 例） |
| 前端 | `npm run lint`（0 error）/ `npm test`（0 失败 + SSR 18/18）/ `npm run build` ✅ |
| **端到端（真实 USGS XML 上游）** | S1 等价默认值 → `<?xml version='1.0' encoding='UTF-8'?><request>…</request>`（**与改造前逐字节一致**）；S2 真实值 → `<?xml version='1.1' encoding='GBK'?><ns:QueryRequest xmlns:ns="http://example.com/svc">…</ns:QueryRequest>`；两者均 200 + RESP 白名单生效 |
| 保存期校验（8 例实测） | 拼错键 / version=1.2 / encoding=UTF-16 / root 含冒号 / root 空值 / `soap` 段 / namespace 缺 uri / JSON 接口配 xml 段 → **全部 `40001` 且消息精确** |

### 14.3 实现期发现（v4.2 设计未预料，已处置）

| # | 发现 | 影响 | 处置 |
|---|---|---|---|
| **I-1** | `SnapshotChangeDiff.text(n)` 用 `n.asText()`，对**对象节点返回空串** → “协议参数变了”会显示成**“无变化”** | 变更详情 / change_note 静默失真 | 已改为对象/数组用 `toString()`（通用修正，其他标量键行为不变） |
| **I-2** | **“既有调用点零改动”过于乐观**：`InterfaceRow` 隐式 canonical 从 21→22 参后，21 参调用**改配 compat 构造器**（`Long groupId` / `int version`）→ 1 处测试报 `int→Long` / `BigDecimal→int` | 编译期暴露（可接受），但“加字段=零改动”不成立 | 该调用点补 `11L` + 末位 `null`；**教训：位置构造 + compat 重载下必须 `clean test-compile`** |
| **I-3** | **增量编译假通过**：`mvn -o -q test-compile` 报 exit=0 但实际未重编（旧 class 残留）；另 `timeout` 命令在 macOS 不存在，导致一次 `mvn` 根本没执行 | 会误判“已通过” | 一律 `mvn -o clean test-compile`（呼应 CLAUDE.md 既有纪律） |
| **I-4** | `root:""` 的口径实现时需定案 | 空串是“不要根元素”还是“用默认”？ | 定案：**键存在但空白 → 40001；键缺失 → 内置默认**（`namespace.prefix` 例外：空串 = 默认命名空间）；前端**只输出非空键**、全默认**不提交该字段** |

### 14.4 文档同步（**已完成**，2026-09-21）

| 文档 | 已同步内容 |
|---|---|
| `doc/表结构设计.html` | `interface` 新增 `protocol_params` 列（含校验纪律 / 语义 A / soap 延后说明）+ 原型映射表新增一行 |
| `doc/API中心使用教程.md` | §4.4 新增「**XML 协议参数**」小节（字段表 / 两条行为 / 校验纪律 / 等价 curl / 排障口径） |
| `doc/开发文档/整体测试方案.md` | §6.6 新增「**X-P 协议参数**」8 例；**更正「边界」**（“根元素写死 request”已不成立 → 新边界为“SOAP 骨架 B2 延后”）；变更记录 v2.4 |
| `CLAUDE.md` | 状态表新增 B1 行；Gotchas 新增 4 条（对象节点 `asText()` 陷阱 / 位置构造与 compat 重载 / `clean test-compile` 纪律 / 协议参数四条纪律） |
| 本方案 | v4.3 §14 完整落地记录（改动清单 / 验证结果 / 实现期发现 I-1~I-4） |

> 顺带修掉：`整体测试方案.md` 两处既有 **GFM 破表**（表格单元里出现未转义的字面 `|`：`<m3\|m4>` 与 `curl … \| grep -E "apicenter_(calllog\|gateway\|…)"`）。
>
> 未改（有意）：`M0-01链引擎契约设计.md` D5 写的是“**首期**一律平台默认参数”——对 M0–M2 阶段仍属实，且为已评审契约文档，不回溯改写。

---

## 附：v4.1 → v4.2 变更摘要（**决策闭合 + 实施范围界定**）

> 本页仅记 v4.2 变更（v4.0→v4.1 的 16 项自审修正见上，仍均为本节上游）。

| 类别 | 变更 | 落点 |
|---|---|---|
| **Q17 闭合** | SOAP 解包**只做响应方向**；入站请求**不解包**（回滚 D-SOAP-7） | D-SOAP-7、§5.2、§7.1 第 1 行、非目标 ⑧ |
| **Q18 闭合** | `faultstring` 截断口径**按 §5.4 ⑤ 表执行**（不再待定） | §5.4 ⑤、用例 24 |
| **Q19 闭合 → 范围变更** | **B2 延后**（待真实样本），**立即实施 = B1** | §9 开头实施顺序、§11 批次归属注、§12.1、§13.1 #11 |
| 表格修复 | §11 表头与数据列数对齐（自审复查发现） | §11 |

---

## 附：v4.0 → v4.1 变更摘要（**定稿自审修正**）

> 自审共 16 项：**3 项真缺陷 + 5 项待明确 + 5 项优化 + 1 项战略质疑 + 2 项新 backlog**。
> **范围与已拍板决策全部不变**；本页只记修正点。

| 类别 | 修正 | 落点 |
|---|---|---|
| **真缺陷** | ① `SoapClientFaultException` **必须直接继承 `RuntimeException`**（否则 `instanceof` 命中 `includes` 白名单 → 继续重试） | §5.4 ①、C5、R9、用例 19 |
| | ② `OutboundEngine` 的 catch 是"**单条 `catch (Exception)` + instanceof 链**"，分支必须放**最前**（否则冒成 `50000`） | §5.4 ② |
| | ③ **`PreStepExecutor` 漏处理** → 会落 `catch(Exception)` 被归 `40001 CONFIG_ERROR`（语义错） | §5.4 ③、§8.1、R10、用例 23、清单 #18 |
| **待明确** | ④ SOAP 解包方向（入站会 warn 刷屏）→ **转 Q17** | §7.1、R12 |
| | ⑤ `faultstring` 截断与取证口径（实测 ~1.5KB）→ **转 Q18** | §5.4 ⑤、用例 24 |
| | ⑥ **未知键 → `40001`**（不忽略；否则静默回落默认 = "配错了但看起来生效"） | §4 校验口径、R11、用例 20 |
| | ⑦ **`encoding` 白名单改为「JDK 支持 且 ASCII 兼容」**，**显式拒绝 `UTF-16`/`UTF-32`**（探针 #9：UTF-16 会产原生非 ASCII 字节） | §1 白名单口径、§4、§7.3、用例 21 |
| | ⑧ 快照 `main` 存**解析后的 JsonNode**，不存 JSON 字符串（否则双重转义） | §6 |
| **优化** | ⑨ 补齐**文档同步**清单（v3.0 重写时丢失） | §9 清单 #13、§8.1 |
| | ⑩ `root`/`namespace` **双语义** → UI 标签动态切换 | §9 清单 #11 |
| | ⑪ 补**直连对照 curl + 带 `protocol_params` 建接口示例**（二分基线） | §10.2 |
| | ⑫ 补 **TL;DR（改什么 / 不改什么 / 怎么验）** | §0 前 |
| | ⑬ `50203` 的**指标结局**归 `upstream_fail`（非 `transport_fail`） | §8.1、用例 25、清单 #19 |
| | ⑭ **把 D-SOAP-1 讲透**（为何不用 MESSAGE 角色） | §3.2 D-SOAP-1 |
| **战略** | ⑮ "完整 SOAP 无真实供应商" → **转 Q19**（设 30 天期限） | §12.1 Q19 |
| **新 backlog** | ⑯ `interface_body.raw`(OUT) **是死配置** → 出站报文模板能力 | §13.1 #9 |
| | ⑰ 异常路径 OUT 调用日志缺 `resp_body` | §13.1 #10 |

| 项 | v4.1 | v4.2 |
|---|---|---|
| 状态 | 3 项待拍板（Q17/Q18/Q19） | **无开放项**（§12 全部闭合） |
| Q17 | 待拍板 | **是**：解包只做**响应方向**（入站不解包）→ D-SOAP-7 / §5.2 / §7.1 / 非目标 ⑧ |
| Q18 | 待拍板 | **按建议**：§5.4 ⑤ 表口径（reason 前 500 + 标注 / payload 全量 / alert 首行）| 
| Q19 | 待拍板 | **B2 延后**（先上 B1）→ §9 实施顺序 / §11 批次归属 / §13.1 #11 |
| **实施范围** | 未明确优先级 | **✅ 立即 = B1（7.4 人日）**；**⏸ 延后 = B2（6.4 人日）**，触发条件 = 真实 SOAP 供应商样本 |
| 文档规模 | 667 行 | **≈680 行**（655+） |
