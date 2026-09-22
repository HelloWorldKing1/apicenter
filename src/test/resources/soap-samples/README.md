# SOAP 真实服务样本集（离线样本 + 复现脚本）

> **定位**：B2（完整 SOAP）的 **G1 材料**（《B2完整SOAP开发计划.md》§1.1）—— 用**真实可调用的公网 SOAP 服务**
> 采集的 WSDL + 请求/响应/Fault **原始报文**，供：① 核对线协议；② 做 WireMock 桩夹具；③ 写集成用例。
>
> **采集时间**：2026-09-21 ｜ **采集方式**：`POST` 直连（脚本见 §5）｜ **全部为公网免密钥服务**
>
> ⚠️ **样本局限（必读）**：这些是**公开演示/便民服务**（.NET / Java 混合），
> **没有一个要求 SOAP `Header`**，也**没有 WS-Security**。企业内网供应商常用 `Header` 做认证/路由
> —— 所以本样本集**不能证明"真实供应商不需要 Header"**（这也是 B2 的 `D-SOAP-8` 仍需真实样本的原因）。

## 0. 类型学盘点（**先分清“XML”到底是什么**）

“XML 协议”不是一种东西。本仓当前用到的 XML 分三类，**只有第三类是 SOAP**：

| 类型 | 规范/版本 | WSDL | 平台当前支持 | 本仓用例 |
|---|---|---|---|---|
| **① POX（Plain Old XML）** | 无统一规范；平台/调用方自定义契约 | ❌ 无 | ✅ 已支持（B1） | B1 手动验收 / 协议矩阵：平台入站 `<request>`、出站 `<?xml …?><request>…`、**ack `<response>`**；M3 回调（POX + HMAC 头） |
| **② XML 文档型 API**（只读、非服务） | **Atom 1.0（RFC 4287）** / RSS 2.0 | ❌ 无 | ✅ 已支持（当普通 XML 解） | 真实上游 **USGS 地震 Atom Feed**（`application/atom+xml`，GET-only）；hnrss（RSS 变体） |
| **③ SOAP** | **SOAP 1.1 / SOAP 1.2**（两套 envelope ns） | **WSDL 1.1**（全样本） | ⏸ **B2 待实现** | 本目录 6 个服务（见 §1） |
| ④ XML-RPC | XML-RPC 规范（`<methodCall>` / `<methodResponse>`） | ❌ 自身无 WSDL | ❌ 不考虑 | **无**（不在设计范围） |

### 样本的精确类型（从样本实测抽取，非推断）

| 维度 | 实测结果 |
|---|---|
| **WSDL 版本** | **6/6 均为 WSDL 1.1**（默认 ns `http://schemas.xmlsoap.org/wsdl/`）；**WSDL 2.0 = 0** |
| **SOAP 版本** | 6/6 支持 **SOAP 1.1**；**5/6 额外暴露 SOAP 1.2 binding**（唯一例外：LearnWebServices Hello → 只有 1.1，发 1.2 得 `VersionMismatch`——与 binding 事实完全对上） |
| **绑定风格 style/use** | **6/6 都是 `document` / `literal`**（**没有 rpc/encoded**） |
| **SOAP Header** | **6/6 的 WSDL 都未定义 `soap:header`** |
| **XML-RPC** | **0 个** |

**三条对 B2 的直接含义**：
1. **WSDL 2.0 不用做**（0/6，且业界几乎无人用）；只需能读 **WSDL 1.1** 的 `soap:` / `soap12:` binding；
2. **document/literal 与平台模型天然契合**（Body 内就是“业务元素 + 子元素”，可直接映射成“根元素 + 扁平字段”）；
   而 **rpc/encoded**（需 `encodingStyle` + 参数带 `xsi:type`）**不在设计范围**（已写入 B2 计划 §1.2 范围纪律）；
3. **Header 缺失是样本偏差**：公开演示服务不要 Header，**企业内网服务常用** —— `D-SOAP-8`（本期不写 Header）仍待真实样本定。

---

## 1. 服务清单与实测结论

| 服务 | 端点 | 协议 | `SOAPAction` 必需？ | 1.2 支持 | WSDL 里有 `soap:header`？ |
|---|---|---|---|---|---|
| DataAccess NumberConversion | `https://www.dataaccess.com/webservicesserver/NumberConversion.wso` | 1.1 + **1.2** | ❌ 不必须（WSDL 里 `soapAction=""`） | ✅ | ❌ |
| Oorsprong CountryInfo | `http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso` | 1.1 + **1.2** | ❌ 不必须（`soapAction=""`） | ✅ | ❌ |
| .NET Calculator（dneonline） | `http://www.dneonline.com/calculator.asmx` | 1.1 + **1.2** | ❌ 不必须（带/不带都 200） | ✅ | ❌ |
| W3Schools TempConvert | `https://www.w3schools.com/xml/tempconvert.asmx` | 1.1 + **1.2** | ❌ 不必须 | ✅ | ❌ |
| LearnWebServices Hello | `https://apps.learnwebservices.com/services/hello` | **仅 1.1** | ❌ 不必须 | ❌ **发 1.2 → `VersionMismatch` Fault** | ❌ |
| 匈牙利国家银行 MNB 汇率 | `http://www.mnb.hu/arfolyamok.asmx` | 1.1 + **1.2** | ❌ 不必须 | ✅ | ❌ |

> **6/6 服务都不强制 `SOAPAction`**；**5/6 支持 1.2**（唯一例外给出 `VersionMismatch` 的真实 Fault）。
> 每个服务的 WSDL 均已存 `wsdl/`（`grep -c '<soap:header'` 结果都是 0）。

## 2. 四条对 B2 有直接影响的发现

### 🔴 C-1 `action` 应为**可选**（推翻 T1 的"1.2 缺 action → 40001"）

- 实测：**带与不带 `SOAPAction`（1.1）/ 带与不带 `action=`（1.2）全部 200**；其中 Oorsprong / DataAccess /
  LearnWebServices 的 WSDL 里 `soapAction=""`（**契约本身就声明为空**）。
- 影响：《B2完整SOAP开发计划.md》T1 原写「1.2 时 `action` 必填 → 40001」——**过严**，应改为
  **可选**；仅在文档/hint 提示"部分企业服务会校验 action，按供应商要求填"。
- 反例参考（为什么仍要保留能力）：WS-I Basic Profile 要求 1.1 带 `SOAPAction`，多数老 .NET 服务按约定校验。

### 🔴 C-2 Fault 分类需**扩 `VersionMismatch` / `MustUnderstand`**（我原表只覆盖 Client/Sender/Server/Receiver）

真实样本（LearnWebServices 收到 SOAP 1.2 报文时）：

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body><soap:Fault>
    <faultcode>soap:VersionMismatch</faultcode>
    <faultstring>A SOAP 1.2 message is not valid when sent to a SOAP 1.1 only endpoint.</faultstring>
  </soap:Fault></soap:Body>
</soap:Envelope>
```
（HTTP **500**，报文是 **1.1 结构**）

- 若按原分类表（"非 Client/Sender → 当 Server 类 → 补偿"），**这个确定性配置错误会被无限补偿**。
- **修正**：`VersionMismatch` / `MustUnderstand` 归**客户端类**（→ 死信、不重试、不计熔断），
  与 `Client` / `Sender` 同组；`Server` / `Receiver` 维持补偿。
- 这也说明"**Fault 的 faultcode 是有限枚举**"（1.1 规范固定 4 个），分类表应**穷举**而不是留"其他"。

### 🟡 C-3 Fault **不是唯一的错误通道**——业务级错误常以 200 + 正常响应返回

| 样本 | 现象 |
|---|---|
| `success/w3schools-bizerror-200.*` | `Celsius=abc` → **HTTP 200**，响应体是 `…Response`，值 `Error`（服务端把错误当业务值返回） |
| `success/oorsprong-unknowncountry-200.resp.xml` | 国家码 `ZZ` → **HTTP 200**，`CapitalCityResult` 为空 |

- 含义：**别指望用 Fault 分类覆盖所有失败**。协议级错误（Fault）与业务级错误（200 里的业务码）是两层，
  后者由平台**既有的 RESP/信封判定**（`ResponseJudger`）负责——两者不要混。
- 同时说明：**"Fault 不一定伴随 5xx"在公开服务上本次未观测到**（6 个服务的 Fault 全是 500），
  所以 B2 的 `D-SOAP-9`（1.2 的 HTTP 200 + Fault 不做）**暂时可维持**，但需记为"未观测到 ≠ 不存在"。

### 🟢 C-4 各服务响应形态差异大 → **解包必须按 localName/结构，不能按前缀**

| 服务 | 响应特征 |
|---|---|
| dneonline / w3schools | 带 `<?xml …?>` 声明、`soap:` 前缀、附 `xsi/xsd` 命名空间 |
| Oorsprong / DataAccess | **无声明**、换行缩进、业务元素用 `m:` 前缀（`<m:CapitalCityResponse>`） |
| LearnWebServices | 无声明、单行、业务元素用**默认命名空间** |
| MNB | 用 `s:` 做 envelope 前缀、业务元素在 **`http://www.mnb.hu/webservices/`**（与请求的 `tempuri.org` 不同！）、附 `i:` 前缀 |

⇒ 印证设计取舍：**`readElement` 取 `localName` 剥离前缀**是对的；**响应命名空间与请求不一致**也是常态
（MNB 就是这样），所以**解包不能要求命名空间匹配**。

## 3. 样本索引（离线夹具）

```
src/test/resources/soap-samples/
├── wsdl/                                       # 6 份真实 WSDL（契约，含 soapAction / soap12 binding 证据）
│   ├── dataaccess-numberconversion.wsdl        # soapAction=""、含 soap12 binding
│   ├── dneonline-calculator.wsdl               # soapAction="http://tempuri.org/Add" 等（非空）
│   ├── learnwebservices-hello.wsdl             # 仅 1.1（无 soap12）
│   ├── mnb-arfolyamok.wsdl
│   ├── oorsprong-countryinfo.wsdl              # 37KB，多操作
│   └── w3schools-tempconvert.wsdl
├── success/                                    # 成功样本（.req.xml / .resp.xml 成对）
│   ├── dneonline-add-11 / -12                  # 1.1 与 1.2 各一份（对照 envelope ns 与 CT）
│   ├── w3schools-c2f-11 / -12
│   ├── oorsprong-capitalcity-11 / -12
│   ├── dataaccess-num2words-11 / -12
│   ├── hello-sayhello-11                       # 仅 1.1 服务
│   ├── mnb-rates-11 / -12
│   ├── w3schools-bizerror-200.*                # 🟡 业务错误走 200
│   └── oorsprong-unknowncountry-200.resp.xml   # 🟡 未知输入走 200
└── fault/                                      # Fault 样本（三种形态）
    ├── dneonline-client-11.*                   # 1.1：faultcode=soap:Client + faultstring(1.5KB 堆栈)
    ├── dneonline-sender-12.*                   # 1.2：Code/Value=soap:Sender + Reason/Text
    └── hello-versionmismatch-11only.*          # 1.1 结构 + VersionMismatch（1.1-only 服务收到 1.2）
```

**用例覆盖对照**（对 B2 测试直接有用）：
`dneonline-*` 可用于 1.1/1.2 正常路径与两种 Fault 的 WireMock 桩（**免公网、确定性**）；
`hello-versionmismatch-11only.*` 是 **C-2 的回归夹具**；`w3schools-bizerror-200.*` 是 **C-3 的回归夹具**。

## 4. 关键样本原文（摘要）

**1.2 Fault（首发发现，`Code/Value` = `soap:Sender`）**
```xml
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" …>
  <soap:Body><soap:Fault>
    <soap:Code><soap:Value>soap:Sender</soap:Value></soap:Code>
    <soap:Reason><soap:Text xml:lang="en">System.Web.Services.Protocols.SoapException: Server was unable to read request…</soap:Text></soap:Reason>
  </soap:Fault></soap:Body>
</soap:Envelope>
```
> 前缀用的是 `soap:` 但绑定 `2003/05`（**前缀无语义**，印证解析必须看命名空间/localName）。

**1.1 Fault**：`<soap:Fault><faultcode>soap:Client</faultcode><faultstring>…</faultstring><detail/></soap:Fault>`（HTTP 500）。

**成功响应（MNB，注意命名空间与请求不同 + `s:` 前缀）**
```xml
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
  <GetCurrentExchangeRatesResponse xmlns="http://www.mnb.hu/webservices/" xmlns:i="…">
    <GetCurrentExchangeRatesResult>…</GetCurrentExchangeRatesResult>
  </GetCurrentExchangeRatesResponse></s:Body></s:Envelope>
```

## 5. 复现命令（随手可跑）

```bash
# 1.1（带 action）
curl -s -X POST http://www.dneonline.com/calculator.asmx \
  -H 'Content-Type: text/xml; charset=utf-8' -H 'SOAPAction: "http://tempuri.org/Add"' \
  -d '<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><Add xmlns="http://tempuri.org/"><intA>1</intA><intB>2</intB></Add></soap:Body></soap:Envelope>'

# 1.2（action 放在 Content-Type 里）
curl -s -X POST http://www.dneonline.com/calculator.asmx \
  -H 'Content-Type: application/soap+xml; charset=utf-8; action="http://tempuri.org/Add"' \
  -d '<?xml version="1.0" encoding="utf-8"?><env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"><env:Body><Add xmlns="http://tempuri.org/"><intA>1</intA><intB>2</intB></Add></env:Body></env:Envelope>'

# 1.2 Fault（intA 传非数字）
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://www.dneonline.com/calculator.asmx \
  -H 'Content-Type: application/soap+xml; charset=utf-8; action="http://tempuri.org/Add"' \
  -d '<?xml version="1.0" encoding="utf-8"?><env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"><env:Body><Add xmlns="http://tempuri.org/"><intA>abc</intA><intB>2</intB></Add></env:Body></env:Envelope>'

# VersionMismatch（向 1.1-only 服务发 1.2）
curl -s -X POST https://apps.learnwebservices.com/services/hello \
  -H 'Content-Type: application/soap+xml; charset=utf-8' \
  -d '<?xml version="1.0" encoding="utf-8"?><env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"><env:Body><HelloRequest xmlns="http://learnwebservices.com/services/hello"><Name>X</Name></HelloRequest></env:Body></env:Envelope>'

# WSDL（注意 w3schools 需要普通浏览器 UA，否则 403）
curl -s -A 'Mozilla/5.0' 'https://www.w3schools.com/xml/tempconvert.asmx?WSDL' | head -20
```

## 6. 对 B2 计划/设计的落地动作（已同步）

| 发现 | 动作 |
|---|---|
| **C-1** `action` 可选 | 改《B2完整SOAP开发计划.md》§3 T1 与验收点：**删除"1.2 缺 action → 40001"**，改为可选 + hint |
| **C-2** Fault 分类穷举 | 改 §2.3 分类表：加 `VersionMismatch` / `MustUnderstand` → 客户端类；并加"1.1 规范固定 4 个 faultcode"说明 |
| **C-3** 业务错误走 200 | B2 无需改动（属既有 `ResponseJudger` 职责）；加一条**测试提醒**：不要用 SOAP Fault 覆盖业务失败用例 |
| **C-4** 解包按 localName | 设计已如此（D-SOAP-3 宽容 + 取 localName）→ 无需改动；补一条**回归夹具**（MNB 式"响应 ns ≠ 请求 ns"） |
| G1 状态 | 由"未取得"→ **"部分满足：6 个真实可调用样本已入库；企业级 `Header`/WS-Security 样本仍缺"** |

> **仍未满足的部分**：真实**企业**供应商（需 WS-Security Header / 双向 TLS / 内网地址）的样本——
> 这才是决定 `D-SOAP-8`（是否做 Header）的关键，需从业务侧取得 WSDL。
