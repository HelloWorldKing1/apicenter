# apicenter · 使用教程

> 版本：v1.1（2026-09-14）｜ 配套：[《API中心项目说明》](API中心项目说明.md) ｜ [README](../../../../README.md)
> 本教程从零开始，带你完成「环境准备 → 启动 → 管理面配置 → 调用验证 → 监控与容错运维」全流程。
> 每步都给出**界面操作**与**等价 curl**两种方式，任选其一即可。

---

## 目录

1. [环境准备](#1-环境准备)
2. [启动服务](#2-启动服务)
3. [10 分钟跑通一条出站链路](#3-10-分钟跑通一条出站链路)
4. [管理面配置详解](#4-管理面配置详解)
5. [入站回调链路](#5-入站回调链路)
6. [测试接口与模拟回调](#6-测试接口与模拟回调)
7. [版本历史、回滚与复制](#7-版本历史回滚与复制)
7.5 [前置步骤（接口编排）](#75-前置步骤接口编排)
8. [监控与容错运维](#8-监控与容错运维)
9. [调用速查（curl）](#9-调用速查curl)
10. [开发与测试](#10-开发与测试)
10.5 [登录与账号（登录 / 注册 / 退出 / 改密 / 账号管理）](#105-登录与账号2026-09-18)
11. [常见问题 FAQ](#11-常见问题-faq)

---

## 1. 环境准备

### 1.1 必需组件

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | **21**（推荐，工程 `--release 21`） | 本仓库 `.java-version` = 21 |
| Maven | 3.9+ | ⚠️ 仓库**无** `mvnw`，需系统安装；若 `mvn -v` 报 command not found，说明装了但未加入 PATH（改用绝对路径或先配 PATH） |
| Node.js | 22 | 前端构建（Vue3 + Vite 5） |
| MySQL | 5.7 / 8.0（PolarDB 兼容） | 连接信息见 `src/main/resources/application.yaml` |
| WireMock | 3.9.1（可选） | 本地模拟供应商 / 回调接收端 |

检查环境：

```bash
java -version        # 期望 openjdk version "21.x"
mvn -v               # 期望 Apache Maven 3.9.x
node -v              # 期望 v22.x
```

### 1.2 数据库准备

数据库已按 `src/main/resources/doc/schema.sql` 建好（**25 张表**）。首次在全新库上部署时：

```bash
mysql -h <host> -u <user> -p <db> < src/main/resources/doc/schema.sql
```

连接信息在 `src/main/resources/application.yaml` 的 `spring.datasource` 中配置。**生产建议**把密码改为环境变量占位：

```yaml
password: ${APICENTER_DB_PASSWORD}
```

### 1.3 加密密钥

凭证使用 AES-256-GCM 加密落库。开发环境密钥已在 `application.yaml` 的 `app.api-center.crypto.key` 提供（base64 编码 32 字节）；**生产必须**通过环境变量注入：

```bash
export APICENTER_CRYPTO_KEY=<base64 编码的 32 字节密钥>
```

密钥缺失时应用启动失败（拒绝明文裸跑）。

### 1.4 （可选）启动本地供应商桩 WireMock

跑演示 / 验收用例时，用 WireMock 模拟供应商：

```bash
java -jar ~/.m2/repository/org/wiremock/wiremock-standalone/3.9.1/wiremock-standalone-3.9.1.jar \
  --port 18080 --root-dir src/test/resources/m4-manual-stubs &
```

> 仓库自带 stub 目录：`src/test/resources/m3-manual-stubs/`（入站回调）、`m4-manual-stubs/`（出站成功 / 熔断 / 超时 / 4xx）、`m5-load/`（压测脚本）。
> WireMock 占用 **18080**，手动验收期间勿同时跑 `mvn test`（集成测试用同一端口）。

---

## 2. 启动服务

### 2.1 启动后端（:8080）

```bash
mvn spring-boot:run
```

或打包后运行：

```bash
mvn package
java -jar target/apicenter-*.jar
```

健康检查：

```bash
curl http://localhost:8080/actuator/health
```

### 2.2 启动前端管理面（:5173，开发模式）

```bash
cd frontend
npm install        # 首次
npm run dev        # http://localhost:5173（/api 代理到 8080）
```

生产构建（产物输出到后端 `src/main/resources/static/`，由 Spring Boot 直接 serve）：

```bash
cd frontend
npm run build      # 之后访问 http://localhost:8080
```

### 2.3 导入演示数据（可选）

种子数据（fastmoss 黄金用例）**默认不自动导入**。需要演示基线时：

```bash
curl -X POST http://localhost:8080/api/admin/seed/import
```

或在 `application.yaml` 中临时置 `app.api-center.seed.enabled=true` 后重启（导入完成后建议改回 `false`）。

导入内容：

- 适配器：无鉴权、直通报文、Bearer Token、信封报文适配、HMAC 回调验签；
- 应用 `fastmoss`（服务地址 `https://openapi.fastmoss.com`）+ 出站 / 回调凭证 + 默认分组；
- 出站接口 `IF-FM-001`（`POST /fastmoss/creatorList` → `/shop/v1/creatorList`，已发布）；
- 入站接口 `IF-FM-CB-001`（`POST /callback/fastmoss/order-state` → `http://localhost:18080/delivery-ok`，已发布）。

---

## 3. 10 分钟跑通一条出站链路

> 目标：用本地 WireMock 模拟供应商，从管理面配一条接口，调用成功后看到统一信封响应。
> 前置：WireMock 已按 §1.4 启动（提供 `/m4-manual-stubs` 中的 `/echo-ok`）。

### 3.1 建应用并启用

**界面**：菜单「应用管理」→「＋ 新建」，填写：

| 字段 | 值 |
|---|---|
| 应用标识 | `QD1` |
| 应用名称 | `演示应用` |
| 服务地址 | `http://localhost:18080`（供应商 baseUrl） |
| 鉴权 / 回调验签 / 默认报文 | 可先留空（走平台默认 Noop 直通） |

保存后状态为「草稿」，点该行「启用」→ 状态「已启用」。

**等价 curl**：

```bash
# 创建
curl -X POST http://localhost:8080/api/admin/apps \
  -H 'Content-Type: application/json' \
  -d '{"appId":"QD1","name":"演示应用","baseUrl":"http://localhost:18080"}'

# 启用
curl -X POST http://localhost:8080/api/admin/apps/QD1/enable
```

> ⚠️ 应用**必须启用**（ENABLED），否则网关调用返回 `40102 应用未启用`。

### 3.2 建分组

**界面**：「分组管理」→「＋ 新建」→ 所属应用 `QD1`、名称 `默认分组`。

```bash
curl -X POST http://localhost:8080/api/admin/groups \
  -H 'Content-Type: application/json' \
  -d '{"appId":"QD1","name":"默认分组","sortOrder":0}'

# 创建返回 data 为空（无 id）；从列表取回 groupId
curl "http://localhost:8080/api/admin/groups?appId=QD1"   # → data[0].id 即 <groupId>
```

### 3.3 建接口并发布

**界面**：「接口管理」→「＋ 新建」，填写：

| 字段 | 值 |
|---|---|
| 接口标识 | `IF-QD-OK` |
| 接口名称 | `演示成功` |
| 接口类型 | 出站中转（默认） |
| 请求方法 | `POST` |
| 平台侧路径 | `/qd/ok`（全局唯一，即网关路由键） |
| 归属应用 / 分组 | `QD1` / `默认分组` |
| 供应商接口路径 | `/echo-ok`（拼接应用服务地址 = `http://localhost:18080/echo-ok`） |
| 入站 / 出站协议 | `JSON` / `JSON` |
| 入站请求体 | 选 `json`，填模板 `{"hello":"world"}` |

保存后点「发布」→ 状态「已发布」。

```bash
curl -X POST http://localhost:8080/api/admin/interfaces \
  -H 'Content-Type: application/json' \
  -d '{
    "code":"IF-QD-OK","name":"演示成功","ifType":"OUTBOUND","method":"POST",
    "path":"/qd/ok","protocolIn":"JSON","protocolOut":"JSON",
    "appId":"QD1","groupId":<上一步返回的 groupId>,
    "upstreamPath":"/echo-ok","timeoutMs":3000,"maxRetries":4,
    "version":1.0,
    "params":[{"side":"IN","name":"hello","type":"string","required":false,"sample":"world","sortOrder":1},
              {"side":"OUT","name":"hello","type":"string","required":false,"sortOrder":1}],
    "bodies":[{"side":"IN","bodyType":"json","raw":"{\"hello\":\"world\"}"}],
    "mappings":[],"fieldDefs":[],"bindings":[]
  }'

# 创建返回 data 为空（无 id）；从列表取回 id
curl "http://localhost:8080/api/admin/interfaces?appId=QD1&keyword=IF-QD-OK"
# → data[0].id 即 <id>

# 发布
curl -X POST http://localhost:8080/api/admin/interfaces/<id>/publish
```

### 3.4 调用验证

```bash
curl -i -X POST http://localhost:8080/qd/ok \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: trace-demo-0001' \
  -H 'X-Biz-Id: biz-demo-0001' \
  -d '{"hello":"world"}'
```

期望：HTTP 200 + 统一信封 `code=0`，`data` 为 WireMock 回显。

到「接口监控」页应能看到：概览统计 +1、调用日志新增一条（方向 OUT、含 traceId、`successRate` 更新）。

> **成功标准**：`{"code":0,"msg":"ok","data":...}`。

---

## 4. 管理面配置详解

### 4.1 应用管理

| 字段 | 说明 |
|---|---|
| ID | 应用数字主键（列表展示 / 运维引用用；接口调用与配置引用一律用「应用标识」） |
| 应用标识 `appId` | 全局唯一，创建后作为定位键（URL、凭证归属、分组/接口引用都用它） |
| 服务地址 `baseUrl` | 供应商根地址，与接口的「供应商接口路径」拼接 |
| 出站鉴权适配器 `authAdapterId` | 应用级默认出站签名适配器（接口级可覆盖） |
| 回调验签适配器 `callbackAuthAdapterId` | 应用级默认回调验签适配器（仅 INBOUND 生效） |
| 默认报文适配器 `defaultMessageAdapterId` | 应用级默认报文适配器 |
| IP 白名单 / 黑名单 | 接入层来源控制，命中拒绝 `40103` |
| QPS 上限 / 日配额 | 接入层限流，超限 `42901` / `42902` |

**凭证在弹窗内直接配置**（v0.2，2026-09-11）：选中「供应商签名适配器 / 回调验签适配器」后，下方**就地展开凭证卡片**，字段由该适配器实现（impl）的元数据决定。

> ⚠️ **两个下拉 + 两张卡片是"角色对应"的，别选错**：
> 「**供应商签名适配器**」→ 「**出站签名凭证**」卡片 → 落库 `kind=OUTBOUND`；
> 「**回调验签适配器**」→ 「**回调验签凭证**」卡片 → 落库 `kind=CALLBACK`。
> 两个下拉**现在各只列出适用的适配器**（如「HMAC 回调验签」只会出现在**回调验签**一侧）。
> 选错的后果是**静默**的：凭证存成 `OUTBOUND`、回调验签为空，直到点「模拟回调」才报
> `应用未配置回调验签凭证（CALLBACK）`（2026-09-22 实测踩到，故加过滤 + 提示）。

> ⚠️ **「解绑」不等于「删除」**：把某个角色的适配器**清空**后，该角色的**凭证不会被自动删除**
> （凭证是独立资源、绑定只是引用；且**明文不可回显**，删了不可恢复，故系统不做"解绑即删"）。
> 此时卡片仍在，并会提示**「该角色当前未绑定适配器：此凭证已不再被使用」**。
> 想真正清掉它：**应用详情 → 凭证区 → 该行先「吊销」（→ `RETIRED`）→ 再「删除」**
> —— **仅 `RETIRED` 可物理删除**（后端守卫，历史清理口径）。
>
> 凭证卡片字段由适配器实现（impl）的元数据决定：

- 单字段（API Key 的 `apiKey`、Bearer 的 `token`、回调 Token）→ 一个密码框；
- 多字段（云厂商签名 `secretId` + `secretKey`）→ 多个密码框，保存时 JSON 化整体加密；
- impl 未声明凭证字段名（如 HMAC 回调验签）→ 退化为单个「密钥 / Token」框；
- **留空 = 不改动**；已配置的只显尾 4 位指纹；「重置」需二次确认（旧值立即失效）。

**凭证状态在哪看**：应用列表已**不再**单列凭证角标（2026-09-12 按使用反馈移除）。巡检时进「编辑」看凭证卡片状态行（`ACTIVE ****尾4位`），或进详情抽屉「凭证区」看完整轮换历史；后端 `hasOutboundCredential` / `hasCallbackCredential` 字段仍保留。

生命周期：`DRAFT → ENABLED → DISABLED → CANCELLED`。

- `enable`：仅 DRAFT / DISABLED 可启用；
- `disable`：仅 ENABLED 可停用；
- `cancel`：仅 DISABLED 可注销。

### 4.2 凭证管理

每个应用下有两类独立凭证：`OUTBOUND`（出站供应商签名）、`CALLBACK`（回调验签）。**管理面永不回显明文**，只显示尾 4 位指纹。

**入口（两种，落库语义相同）**：

| 入口 | 适用 | 能力 |
|---|---|---|
| 应用弹窗（新建 / 编辑）→ 凭证卡片 | 首次配置、供应商换密钥后一步更新 | 填写 + 选「更新 / 重置」；随应用保存一并提交 |
| 应用详情抽屉 → 凭证区 | 查看历史、轮换全套操作 | `prepare` 生成 / 激活 / 完成轮换 / 吊销 / 删除历史 |

> 新建应用时应用尚不存在，凭证在「保存应用」成功后由同一次操作**串行写入**（凭证失败会有明确提示，重新进入编辑补填即可）。

> 下表端点均以 `/api/admin/apps/{appId}/credentials` 为前缀（表中 `...` 即该前缀）。

| 操作 | 端点 | 语义 |
|---|---|---|
| 生成新凭证 | `POST .../credentials/prepare` | 平台生成随机值，状态 `ROTATING`，明文仅本次回显 |
| 激活 | `POST .../credentials/{id}/activate` | 旧 ACTIVE → ROTATING 并存 24h，新 → ACTIVE |
| 一步更新 | `POST .../credentials/update` | 录入供应商给的新密钥；新 → ACTIVE，旧 → ROTATING 并存 24h |
| 重置（应急） | `POST .../credentials/reset` | 新 → ACTIVE，旧全部立即 `RETIRED` |
| 即时失效 | `POST .../credentials/{id}/retire` | 泄漏应急，立即失效 |
| 完成轮换 | `POST .../credentials/{id}/finish-rotation` | ROTATING → RETIRED 提前收尾 |
| 删除 | `DELETE .../credentials/{id}` | 仅 RETIRED 可删 |

**出站轮换推荐流程**：`prepare`（拿明文去供应商侧配置）→ `activate`（平台切换签名，旧值验签并存 24h）→ 观察无异常 → `finish-rotation`。

**回调密钥轮换**：管理员从供应商拿到新密钥后直接 `update`（验签新旧并存）。

### 4.3 适配器管理

- 「适配器」页可查看 / 新增实例，`impls` 元数据决定参数表单（前端动态渲染）。
- 凭证类参数（如 `apiKey` / `token` / `secretKey`）**不落适配器 params**，统一在「应用管理 → 新建/编辑应用 → 凭证卡片」维护。
- 常用做法：优先使用 seed 提供的通用适配器实例；确需定制时新建，`adapter.name` 必须全表唯一。

### 4.4 调用方管理（平台客户鉴权，2026-09-23）

> **调用方 = 平台客户**（如 ERP）—— 与「应用（供应商）」方向相反：应用是"平台替你去调谁"，调用方是"谁在调平台"。
> 侧边栏「**调用方管理**」（图标 ◫）里维护；**鉴权是否强制**由平台级开关 `app.api-center.client-auth.mode` 决定
> （`OFF` 默认不校验 / `OPTIONAL` 有凭证就验 / `ENFORCED` 必须通过，**改后需重启**）。

**① 建调用方**：「＋ 新建调用方」→ 填**调用方标识**（3~32 位大写字母/数字/`-`/`_`，如 `DEMO-CLIENT`）、名称、联系人；
「鉴权适配器」选一种入站鉴权方式：

| 方式（适配器） | 调用方要带什么 | 需要凭证 |
|---|---|---|
| **调用方 API Key 验签** | `X-Client-Id` + `X-Api-Key: <密钥>` | ✅ `API_KEY` |
| **调用方 HMAC 验签** | `X-Client-Id` + `X-Timestamp` + `X-Signature`（签名口径**与供应商回调验签完全一致**） | ✅ `HMAC_SECRET` |
| **调用方 Bearer 验签** | `X-Client-Id` + `Authorization: Bearer <token>`（`prefix` 可置空 = 裸 token） | ✅ `BEARER_TOKEN` |
| **调用方 IP 名单** | 只需 `X-Client-Id`（用于审计主体），**靠 IP 白名单放行** | ❌ 不需要 |

再按需填 **IP 白名单 / 黑名单**（英文逗号分隔；**黑名单优先**；与应用维度的 IP 名单是 **AND 叠加**）。
保存后应用即 `ENABLED` ⇒ **在强制模式下会立刻开始校验**。

> ⚠️ **未配置鉴权方式 = fail-closed**：`ENFORCED` 下没有适配器的调用方**一律拒绝（40108）**——
> 这是刻意的（"没配=放行"是常见的鉴权事故），**不会**像接口绑定那样逐层回退到"无鉴权"。

**② 配凭证**：列表该行点「**凭证**」→ 抽屉里选类型（API Key / HMAC 密钥 / Bearer Token / Basic）→
「**生成新凭证**」→ **明文只在这一次显示**，请立即交给调用方；之后只显示尾 4 位指纹。
轮换与「应用凭证」同一套语义：**新增（ROTATING，待激活）→ 激活（旧凭证转 ROTATING 并存 24h）→ 完成轮换 / 吊销 → 删除（仅已失效可删）**。

**③ 验证**：用 §9.1 的 curl（带鉴权头）调一个已发布的出站接口 —— 成功即 `200`；失败 `401` 且 `msg` 里带错误码。
**④ 看留痕**：「接口监控 → **接入鉴权**」Tab 能按主体 / IP / 结果 / 方式筛，看每次判定的**主体名 · 来源 IP · 方式 · 结果 · 错误码**；
点审计行的 `traceId` 可跳到「调用日志」看这次调用实际发了什么（"**谁调的**"↔"**调了什么**"串起来）。

**⑤ 应急回退**（立刻生效，无需重启）：两种粒度 ——
· **停用单个调用方**（列表「停用」）⇒ 该调用方请求立即 40107；
· **整体退回不校验**（把 `client-auth.mode` 改回 `OFF` 后重启）。

### 4.5 接口管理

**请求参数（Params）**

- `side = IN`：入站侧（调用方 / 供应商回调进来的字段）；
- `side = OUT`：出站侧（转发给供应商 / 送达给回调地址的字段）；
- 字段：名称、类型、是否必填、示例值、排序。

**请求体（Bodies）**

- `side = IN`：入站请求体（json / form / xml / raw）；
- `side = OUT`：出站转发方式（透传 / 模板）。

**快速导入参数（2026-09-12）**

「请求参数」tab 的每一侧 Params 面板都有 `⇪ 快速导入参数`：粘贴一段真实请求 JSON（也支持 `a=1&b=2` 形态），自动推断出参数行。

- **映射**：嵌套用点号（`filter.seller_id`）、数组元素取首个样本（`list[0].id`）；对象/数组本身也会生成一行（`object` / `array`）；
- **必填**：默认全部必填，`null` 与空容器（`{}` / `[]`）为非必填；可切「仅顶层必填」；
- **示例值保真**：示例值取报文原始字面量 —— 19 位 id（如 `7494312521977267257`）不会被改写；`1.10`、`1e3` 也原样保留；
- **校验**：粘贴后 300ms 自动校验，非法 JSON 会给出行/列与原因（如「内容未闭合：还缺 2 个 ]}」）并可「定位」；括号不匹配、顶层多余内容、顶层标量都会明确报错；
- **写回**：默认「覆盖同名并追加」，导入后可用一次「撤销导入」回退；导入只写参数，**不会**自动生成字段映射规则。

**字段映射（Mappings）** —— 方向恒为「入站 → 出站」：

| 操作 op | param | 说明 |
|---|---|---|
| `rename` | — | 字段重命名 |
| `typeCast` | 目标类型 | 类型转换 |
| `enumMap` | 映射表，如 `PAID→1,DONE→2` | 枚举值映射 |
| `default` | 默认值 | 常量注入（source 可空） |
| `condition` | Aviator 表达式 | 条件取值（沙箱，纯解释器防注入） |
| `aggregate` | 聚合方式 | 聚合 |

`nullStrategy` 四值：保留原值 / 置空 / 默认值 / 报错。

> **空映射 = 整体透传**（M0-02 D3）；非空白名单时必须显式 `rename` 保留需要透传的字段。

**响应 / ack 字段（FieldDefs）**

- `kind = RESP`：出站响应字段（仅 OUTBOUND）；
- `kind = ACK`：ack 回执字段（仅 INBOUND）。

**XML 协议参数（2026-09-21）** —— 位置：接口弹窗 → **高级** → 「XML 协议参数」（**仅「出站协议」= XML 时显示**；
入站不给入口，因为入站请求方向**不解包**——`入站XML/出站JSON` 时这些参数永不生效，不给“配了不生效”的口子）

| 项 | 可选值 | 留空 / 默认 | 说明 |
|---|---|---|---|
| **XML 类型** | **普通 XML（POX）** / **SOAP 1.1** / **SOAP 1.2** | 普通 XML（POX） | **决定“请求怎么包 / 响应怎么解 / Fault 怎么读”**；选 SOAP 后下方多出三个子项 |
| XML 声明 version | `1.0` / `1.1` | `1.0` | 写进出站报文的 `<?xml version="…"?>`（**与 SOAP 版本无关**） |
| XML 声明 encoding | `UTF-8` / `GBK` / `GB2312` / `GB18030` / `Big5` / `Shift_JIS` / `ISO-8859-1` / `US-ASCII` | `UTF-8` | 写进声明（**不改变字节**，见下） |
| 根元素 / **Body 内业务元素** | 合法 XML 元素名 | `request` | 标签**随类型变**：普通 XML = 文档根元素；SOAP = `<Body>` 内业务元素 |
| 命名空间 URI | 任意合法 URI | 不写命名空间 | 留空则不输出 `xmlns` |
| 命名空间前缀 | 合法前缀；**留空 = 默认命名空间** | 默认命名空间 | 前缀 `ns` → `<ns:QueryRequest xmlns:ns="…">`；留空 → `<QueryRequest xmlns="…">`。⚠️ **需先填「命名空间 URI」**——没有 URI 的前缀无意义，该输入框在 URI 为空时会**禁用**（避免"填了却没保存"） |
| ① SOAP `action`（可选） | 任意串 | 空（不发） | 1.1 → `SOAPAction: "…"` 头；1.2 → `Content-Type` 的 `action="…"`。**实测 6/6 公开服务不强制**，可留空 |
| ② SOAP envelope 前缀 | 合法前缀 | `soap` | 包在 `Envelope`/`Body` 上的前缀（前缀无语义，绑定的是命名空间） |
| ③ 响应解包 Envelope | 开 / 关 | **开** | 开后：业务字段直接在 `data` 根（`Envelope/Body` 被剥）；关：保留层级（`data.Body.…`）。⚠️ 关掉后若你声明过「出站响应字段」（**按顶层匹配的白名单**），`data` 会是**空对象**（层级的顶层是 `Body`）—— 不是缺陷 |

**三条必须知道的行为**：

1. **`encoding` 只改「声明」，不改字节**：非 ASCII 文本会以**字符引用**（如 `&#x4e2d;`）输出，字节**恒为 ASCII 安全**。
   这能满足“要求声明必须是 GBK”的供应商（声明与实际字节都合法、任何合规解析器都能读）；
   但**产不出原生 GBK 字节** —— 若供应商用字符串切割而非 XML 解析器处理报文，当前不支持（已记 backlog）。
   注：`UTF-16` / `UTF-32` 会被**显式拒绝**（它们会产生非 ASCII 原生字节，破坏上述前提）。
2. **生效时机**：保存即生效（平台会失效该接口的链缓存，**无需重启、不依赖 5 分钟 TTL**）；
   且参数会**进版本快照** → 「版本历史 → 回滚」能一并回退协议参数。
3. **SOAP 的 Fault 会被分类**（这是选对类型的主要收益）：供应商回的 SOAP Fault 会按代码分流——
   `Client`/`Sender`/`VersionMismatch`/`MustUnderstand` 属**靠我们请求错了**，→ **死信（`50203`）、不重试、不计熔断**；
   `Server`/`Receiver` 与普通 5xx 维持重试+补偿。详见《B2完整SOAP开发计划.md》§2.3。

**两个 SOAP 特有的坑（实测）**：

- **版本选错会得到 `VersionMismatch`**：向**只支持 1.1** 的服务发 1.2 报文，供应商会回 HTTP 500 + `faultcode=soap:VersionMismatch`。
  ⇒ 不确定时**先用 SOAP 1.1**（实测样服 6/6 支持 1.1，仅 5/6 支持 1.2）；且该 Fault 会正确归为客户端类死信，**不会无限重试**。
- **`ack` 不会被 SOAP 包裹**（设计如此）：入站回调接口的 ack 回执仍是约定的 `<response>`，只有**出站报文**才包 `Envelope`。

**校验纪律（不静默忽落默认）**：写错参数会**直接拒绝保存**（`40001`）而非默默用默认值 ——
未知键（如把 `root` 拼成 `rootEelement`、或在 `soap` 里再写 `version`）、`version=1.2`、`encoding=UTF-16`、
根元素含冒号或为空、命名空间缺 URI、**类型选普通 XML 却出现 `soap` 配置**（互斥）、**JSON 协议的接口配了 `xml` 段** → 全部 `40001`。
想用平台默认就**不要填**该项，而不是填空串。

**等价 curl**（改已有接口；`protocolParams` 为 JSON **字符串**）：

```bash
# 只改协议参数（其余字段需整量提交，此处略；完整示例见 §9.3）
# 例：SOAP 1.1（type 是唯一真相；soap 段不含 version）
curl -X PUT http://localhost:8080/api/admin/interfaces/123 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'X-Change-Note: XML 改 1.1 + 命名空间' \
  -d '{"code":"IF-XML","name":"XML 接口","ifType":"OUTBOUND","method":"GET",
       "path":"/x","protocolIn":"XML","protocolOut":"XML",
       "appId":"USGSXML","groupId":1,"upstreamPath":"/x.atom","status":"PUBLISHED",
       "timeoutMs":15000,"maxRetries":2,"version":1.0,
       "protocolParams":"{\"xml\":{\"type\":\"SOAP_1_1\",\"root\":\"Add\",\"namespace\":{\"uri\":\"http://tempuri.org/\"},\"soap\":{\"action\":\"http://tempuri.org/Add\"}}}",
       "params":[],"bodies":[],"mappings":[],"fieldDefs":[],"bindings":[],"steps":[]}'
```

> **排障口径**：想确认平台到底发了什么，看**调用日志的 OUT 条 `req_body`**（那是编码后的实录）——
> 例：`<?xml version='1.1' encoding='GBK'?><ns:QueryRequest xmlns:ns="http://example.com/svc">…</ns:QueryRequest>`。
> 注意 GET/DELETE **不携带请求体**（协议层不发 body），此时 `req_body` 仅代表“编码产物”而非“已发送内容”。

**适配器绑定（Bindings）**

- `MESSAGE`：报文适配器；`AUTH`：出站签名（仅 OUTBOUND）；`CALLBACK_AUTH`：回调验签（仅 INBOUND）。
- `adapterId` 留空 = 继承应用默认；都为空 = 平台 Noop 直通。

---

## 5. 入站回调链路

> 目标：供应商回调平台 → 平台验签 → 适配 → 送达回调地址 → 立即回 ack。

### 5.1 建入站回调接口

**界面**：「接口管理」→「＋ 新建」，接口类型选「入站回调」：

| 字段 | 值 |
|---|---|
| 接口标识 | `IF-QD-CB` |
| 平台侧路径 | `/qd/callback`（供应商要回调的路径） |
| 请求方法 | `POST` |
| 回调地址 | `http://localhost:18080/delivery-ok`（你的业务接收端） |
| 入站参数 | 如 `event_id` / `order_id` / `state` |
| 出站参数 | 如 `event_id` / `order_id` / `order_state` |
| 字段映射 | `state → order_state`（rename） |
| ACK 回执字段 | `returnCode`(number)、`returnMsg`(string) |
| 绑定 | `CALLBACK_AUTH` → `ADP-301`（HMAC 回调验签） |

发布前需先给该应用配置 `CALLBACK` 凭证（否则验签无密钥）：

```bash
curl -X POST http://localhost:8080/api/admin/apps/QD1/credentials/update \
  -H 'Content-Type: application/json' \
  -d '{"kind":"CALLBACK","credential":"my-callback-secret"}'
```

保存后「发布」。

### 5.2 模拟供应商回调

供应商回调需携带签名头。用管理面「**模拟回调**」按钮最省事（见 §6.2），或手动签名：

```bash
TS=$(date +%s)
BODY='{"event_id":"evt-1","order_id":"ORD-1","state":"PAID"}'
# 签名串 = timestamp + "." + UTF-8(rawBody)——中间是【英文句点】，漏掉必然验签失败（40100）
# 用 openssl 一行算出（printf 不加换行，保证 body 逐字节一致）：
SIG=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac 'my-callback-secret' -r | cut -d' ' -f1)

curl -i -X POST http://localhost:8080/qd/callback \
  -H 'Content-Type: application/json' \
  -H "X-Timestamp: $TS" \
  -H "X-Partner-Signature: $SIG" \
  -d "$BODY"
```

> 签名算法与容差在**适配器实例的 params** 里调整（「适配器」页 → 对应 HMAC 回调验签实例，非应用弹窗）：`signatureAlgorithm`（默认 `HMAC-SHA256`，另支持 `HMAC-SHA1` / `HMAC-SHA512`）、`timestampToleranceSeconds`（默认 300）、`replayProtection`（默认关闭；开启后按 `(appId, signature)` 在容差窗口内内存去重，单实例口径）。平台侧实现见 `HmacSigner.sign()`（hex 小写），自算签名不一致时以它为准。

期望：

- 平台**立即返回 ack**（供应商视角成功）；
- `inbound_delivery` 落一条 `RECEIVED` → 送达成功转 `ACKED`；送达失败仍回 ack，转 `PENDING` 由补偿 worker 重送。

> **常见误解**：「ack 成功但送达 PENDING」是**正常态**，不是 bug——ack 与送达解耦是设计使然。

### 5.3 回调地址安全

- `callback-allow-private: true` 时允许内网 / 回环地址（开发 / 测试指向本地 WireMock 必须开）。
- **生产必须设为 `false`**（SSRF 防护，同时作用于保存校验与运行时送达兜底）。

---

## 6. 测试接口与模拟回调

### 6.1 测试接口（出站调试）

「接口管理」详情 →「测试接口」，以给定请求体**真实走一遍出站链路**（含状态机），但不要求接口已发布、不做方法校验，草稿态也可测。

> **请求体的格式 = 该接口的「入站协议」**（这个工具扮演的是**调用方**）：入站 JSON 就填 JSON（`Content-Type: application/json`），
> **入站 XML 就填 XML**（`Content-Type: application/xml`）。格式不符时前端会直接给出可读提示
> （例如"请求体不是合法 JSON（该接口「入站协议」是 JSON）"），不会抛 `Unexpected token '<'…` 这类原文。
>
> **格式不符会实时提示（2026-09-22）**：内容与「入站协议」不一致时，输入框下方**立刻**出现黄条
> （如"当前内容不是 XML … 直接发送会被后端拒：40002 报文格式非法"）—— 不必靠"点了才知道"。
>
> **输入框自带结构美化（2026-09-22）**：上方显示格式标签（`XML · 按「入站协议」` / `JSON · 按「入站协议」`）
> 与该填什么的提示（**含填错会看到的报错码**，如 XML 接口填 JSON → `40002 报文格式非法`）；
> 点「**美化结构**」即按 XML / JSON **无损缩进**（**只增删空白**：19 位数字、`1.10`、重复键都不被改写）。
> **美化失败不会吞掉你的输入**：内容保持原样 + 给出原因。另外预填内容**在打开弹窗时就已自动美化**，
> 预填骨架随协议（XML → `<request></request>`；JSON → `{}`），输入框为**深色等宽编辑器**样式。

```bash
curl -X POST http://localhost:8080/api/admin/interfaces/<id>/test \
  -H 'Content-Type: application/json' \
  -d '{"hello":"world"}'
```

响应 data 为 `{chainTrace, result}`：

- `chainTrace`：本次装配出的适配器链（role / adapterId / impl / version），test 端点**强制实时解析**，永远反映当前配置；
- `result`：原业务结果。

> ⚠️ `{chainTrace, result}` 只在**拿到业务结果**时成立（包括供应商返回错误、`code != 0` 的信封——此时仍有 chainTrace）。若链路直接**抛异常**（4xx 死信 `50201` / 熔断短路 `50202` / 超时 `50401`），响应由全局异常处理返回错误信封、`data` 为 `null`、**不带 chainTrace**，诊断信息看 `msg`（如死信编号）。

> 注意：调试端点有意**不落 IN 条 `call_log`**，避免调试流量污染成功率口径；OUT 条照常落库。

### 6.2 模拟回调（入站调试）

「接口管理」详情 →「模拟回调」（仅 INBOUND，且接口必须已发布）：按应用 `CALLBACK` 凭证自动签名，自调平台真实网关路径
（**回调报文格式同样 = 该接口的「入站协议」**：入站 XML 就填 XML；输入框同样带**格式标签 / 该填什么的提示 / 「美化结构」按钮**）。
> 前置：该接口所属**应用**必须绑了**回调验签适配器**并配了 **CALLBACK 凭证**，否则直接报
> `应用未配置回调验签凭证（CALLBACK）`（别把它选进「供应商签名」—— 见 §4.2 的凭证卡片说明）。
> ⏱️ **会等几秒**：网关侧的**入站送达是同步的**，回调地址不可达时会按该接口「最大重试」做内联短重试
> （退避 200/400/800/1600ms，4 次 ≈ 3s）—— 这是「ack 与送达**成败**解耦」（送达成败不影响 ack），但**时延上仍有耦合**。返回：

- `traceId`
- `ackStatus` / `ackContentType` / `ackBody`（供应商视角 ack）
- `deliveryStatus`（按 traceId 回查的送达状态）

```bash
curl -X POST http://localhost:8080/api/admin/interfaces/<id>/test-callback \
  -H 'Content-Type: application/json' \
  -d '{"event_id":"evt-1","order_id":"ORD-1","state":"PAID"}'
```

---

## 7. 版本历史、回滚与复制

### 7.1 版本快照

每次配置内容变更（创建 / 更新 / 回滚）都会生成新版本快照，版本号从 **v1.0** 起、每次 **+0.1**，**只增不回退**（回滚也产生新版本号，不复用旧号）。发布 / 下线只改生命周期状态，不产生新版本。

变更说明通过 `X-Change-Note` 请求头传入：

```bash
curl -X PUT http://localhost:8080/api/admin/interfaces/<id> \
  -H 'Content-Type: application/json' \
  -H 'X-Change-Note: 调整读超时 3000→5000' \
  -d '{...完整接口定义，version 填当前版本...}'
```

### 7.2 查看版本历史 / 快照详情

```bash
curl "http://localhost:8080/api/admin/interfaces/<id>/versions?page=1&pageSize=10"
curl "http://localhost:8080/api/admin/interfaces/<id>/versions/1.0"
```

界面：「接口管理」详情 →「版本历史」，每行可看「变更详情」（结构化 diff）与「快照」。

### 7.3 回滚

**界面**：版本历史选中目标版本 →「回滚到此版本」→ 确认。

```bash
curl -X POST http://localhost:8080/api/admin/interfaces/<id>/rollback \
  -H 'Content-Type: application/json' \
  -d '{"targetVersion":1.0,"currentVersion":1.3}'
```

- 回滚 = 目标快照全量替换 + 乐观锁（`currentVersion` 不匹配返回 `40001`）；
- 目标版本缺失返回 `40403`；
- 新版本说明为「回滚至 v1.0」。

### 7.4 复制接口

```bash
curl -X POST http://localhost:8080/api/admin/interfaces/<id>/copy \
  -H 'Content-Type: application/json' \
  -d '{"code":"IF-QD-OK2","path":"/qd/ok2"}'
```

- 新接口为源当前配置的副本，归属固定 = 源应用 / 源分组（不支持跨应用）；
- 产物为 `DRAFT v1.0`，首快照说明为「复制自 {源code}#v{源版本}」；
- 未填的 `name` / `upstreamPath` / `callbackUrl` 沿用源值。

---

## 7.5 前置步骤（接口编排）

> 目标：新接口 A 在调自己的供应商之前，**先用一个已维护好的接口 B**（取 token / 主数据 / 额度校验），
> 把 B 的结果映射进 A 的出站报文（`A → B → 第三方`）。详见《开发文档/设计/前置接口编排设计方案.md》。

### 7.5.1 界面操作

1. 先确认 B 是**已发布**的出站中转接口（未发布的接口不能作为前置）；
2. 接口管理 → 新建/编辑接口 A → Tab「**前置步骤**」（仅出站中转可见）→ `＋ 添加前置步骤`：
   - **步骤名**：如 `auth`（字母/数字/下划线，≤32；将作为命名空间 `steps.auth.*`）；
   - **前置接口**：选 B；
   - **失败策略**：一期仅「阻断后续（ABORT）」；
   - **启用**：可临时停用（保留配置不执行）；
3. 可添加多步（≤5），用 ↑↓ 调整顺序（顺序敏感：先取 token 再带上 token）；
4. 回到「字段映射」Tab 写引用：`source = steps.auth.token` → `target = api_token`；
   - 「入站字段」下拉里会有分组「**前置步骤 · auth（B的标识）**」，可直接选 `steps.auth.*`（选项来自 B 的 RESP 出站响应字段 + 出站侧参数；
     B 未发布/已删除时会提示「字段未读到」，此时可手动输入路径）；
   - 也可点步骤 Tab 里该步的「**可用字段**」→ 复制路径后粘贴；
5. 保存（随保存生成新版本快照，可在版本历史回滚；复制接口会带上步骤）。

### 7.5.2 等价 curl

```bash
# A 挂两步前置：auth（IF-AUTH-001）、route（IF-ROUTE-003）
curl -X PUT http://localhost:8080/api/admin/interfaces/<A的id> \
  -H 'Content-Type: application/json' -H 'X-Change-Note: 加两步前置' \
  -d '{"code":"IF-ORDER","name":"下单","ifType":"OUTBOUND","method":"POST","path":"/order",
       "protocolIn":"JSON","protocolOut":"JSON","appId":"fastmoss","groupId":1,
       "upstreamPath":"/v1/order","version":1.0,
       "mappings":[{"source":"steps.auth.token","op":"rename","target":"api_token","nullStrategy":"KEEP","sortOrder":0}],
       "steps":[{"seq":0,"stepCode":"auth","targetInterfaceId":<B的id>,"failurePolicy":"ABORT","enabled":true}]}'
```

### 7.5.3 运行语义（排障必读）

| 情形 | 表现 |
|---|---|
| 正常 | B 先调，结果立刻合入模型；A 的映射可引用；`X-Trace-Id` 贯穿 A/B/第三方（监控按 traceId 能看到瀑布） |
| B 返回 4xx / 业务失败 / 未发布 / 目标缺失 / 链过深 | A **不推进状态机**（记录停留 INIT），响应 40001 并在 msg 里点名是哪一步（不建死信、不入补偿） |
| B 5xx / 429 短重试耗尽、B 熔断 OPEN | A 转 **COMPENSATING 顺延**（50201 / 50202），由补偿 worker 重放时**重跑前置** |
| B 读超时 | A 转 **UNKNOWN**（50401，结果不确定，需人工对账）——不会自动重试 |
| 前置接口被下线 | A 运行时硬失败（40001“目标接口未发布”）；下线时服务端会回 warnings 强提示 |
| 监控 | 状态链出现 `前置步骤` 节点（trigger=PRE_STEP，含步骤名/HTTP 码/耗时）；调用日志按 traceId 可看到 B 的 OUT 记录 |
| 应急关闭 | `app.api-center.pre-step.enabled=false`：装配期忽略步骤（**配置保留**、不执行）——一键回到今日行为 |

### 7.5.4 行为边界

- 前置调用**不消耗**宿主应用的 QPS / 日配额（内部调用，不经接入层防护）；
- 前置调用**不计入**前置接口所属应用的 QPS / 配额统计；
- 前置**不落自己的运行记录**，也不进补偿/对账状态机——长重试统一由宿主驱动（重放依赖供应商对业务键幂等，ADR 5）；
- 一期不支持：失败继续（CONTINUE）/ 兼容默认值（FALLBACK）、入参常量覆盖、条件执行、并行组、拖拽排序。

---

## 8. 监控与容错运维

菜单「接口监控」（`/api/admin/monitor`）与「概览」页。

### 8.1 概览与统计

```bash
curl http://localhost:8080/api/admin/monitor/overview
curl "http://localhost:8080/api/admin/monitor/stats/trend?range=24h"
curl "http://localhost:8080/api/admin/monitor/stats/top-interfaces?range=24h&limit=8"
```

`overview` 返回：今日调用量、成功率、成功数、死信数、待补偿数、待重送数、死信积压、UNKNOWN 数。

### 8.2 调用日志

```bash
# 列表（已瘦身：不含报文体，取字段走详情）
curl "http://localhost:8080/api/admin/monitor/call-logs?direction=OUT&appId=QD1&statusGroup=5xx&page=1&pageSize=20"

# 详情（含 req_headers / req_body / resp_body；界面抽屉打开时按 id 拉取）
curl "http://localhost:8080/api/admin/monitor/call-logs/<id>"
```

- 过滤：traceId / 接口 / 方向 / 应用 / HTTP 状态（精确 `statusCode` 优先，或 `statusGroup=2xx|4xx|5xx` 分组）/ 时间范围 / 关键字；
- **列表响应不含 `req_headers` / `req_body` / `resp_body`**，报文正文一律走详情端点；
- `keyword` 走 `url LIKE`（无法走索引），服务端**强制时间窗 ≤7 天**：未传按近 24h，跨度超出按近 7 天截断（保留结束时间）；
- 日志明细中敏感字段已脱敏（无明文密钥）。

**界面明细（v0.3）**：调用日志行「明细」→ 抽屉内请求体 / 响应体**默认缩进美化**（JSON / XML 自动识别，2 空格缩进），工具栏可切换「原文」逐字节核对、一键复制；报文超 4096 字符落库已截断时会标「已截断」，仍按缩进展示；表单编码 / 二进制等非 JSON·XML 报文原样展示。状态机 Tab 的入站 / 出站 / 响应报文与仪表盘「最近调用日志」抽屉同能力。
> ⚠️ **两个字段口径不同，别当数据不一致**：调用日志的**请求体** = **编码产物**（平台编出来的报文，无论是否真发送）；状态机的**出站报文 `out_payload`** = **实际发送的报文**。**GET / DELETE 不携带请求体** ⇒ `out_payload` 恒为空（界面会给出说明），而调用日志的请求体照常有值。

### 8.3 出站状态链

```bash
curl "http://localhost:8080/api/admin/monitor/outbound-requests?status=UNKNOWN"
curl "http://localhost:8080/api/admin/monitor/outbound-requests/<id>"
```

详情抽屉顶部为**状态链时间线**（`INIT → MAPPING → 终态`，末节点带「当前」角标）：

- `trigger` 枚举：`FIRST_SEND` / `COMPENSATE` / `CIRCUIT_OPEN` / `RECONCILE_MANUAL` / `TTL_DOWNGRADE` / `REPLAY` / `EXHAUSTED`；
- `SENDING / RETRYING` 不产生节点，短重试次数并入终态 detail；
- 上线前的历史记录无状态链（只显示当前状态一个节点）。

### 8.4 UNKNOWN 人工对账

```bash
# 查询对账审计
curl http://localhost:8080/api/admin/monitor/outbound-requests/<id>/audits

# 人工置位：已到达 → SUCCESS；未到达 → COMPENSATING（立即入补偿队列）
curl -X POST http://localhost:8080/api/admin/monitor/outbound-requests/<id>/reconcile \
  -H 'Content-Type: application/json' \
  -d '{"target":"SUCCESS","operator":"alice","reason":"供应商后台已确认收到"}'
```

- `source=MANUAL` 落 `reconcile_audit`；
- 未在 TTL（默认 10 分钟）内人工处理时，自动降级为 `COMPENSATING`（`source=TTL`）。

### 8.5 死信查看与重放

```bash
curl "http://localhost:8080/api/admin/monitor/dead-letters?bizType=OUTBOUND&status=PENDING"
curl -X POST http://localhost:8080/api/admin/monitor/dead-letters/<id>/replay
```

- 死信来源：4xx（非 429）直接死信；补偿超最大次数转死信；
- 界面可点「报文」预览原始出站报文；
- **重放 = 重新入队**（状态重置后由补偿 worker 自然重放）。

### 8.6 告警

```bash
curl "http://localhost:8080/api/admin/monitor/alerts"
curl http://localhost:8080/api/admin/monitor/alert-rules
```

告警规则可配置指标 / 阈值 / 通知渠道 / 启停。内置告警：验签连续失败（5 分钟窗口，阈值见 `verify-fail-alert-threshold`）。通知渠道首期仅随事件记录，SMTP / webhook 对接 v1.1。

### 8.7 指标与健康

- Prometheus 指标：`http://localhost:8080/actuator/prometheus`
- 健康检查：`http://localhost:8080/actuator/health`
- 日志行携带 `traceId`（MDC，同源于运行表 `trace_id` 与 `call_log.trace_id`）。

---

## 9. 调用速查（curl）

### 9.1 出站调用（Flow A）

```bash
curl -X POST http://localhost:8080/<平台侧路径> \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: <可选，自定义链路 ID>' \
  -H 'X-Biz-Id: <可选，业务键，供应商幂等依赖>' \
  -d '<请求体>'
```

**若平台启用了「调用方鉴权」**（`app.api-center.client-auth.mode` 非 `OFF`，2026-09-23 起支持），还要带鉴权头
（即「调用方管理」页里为该调用方配置的方式与凭证）：

```bash
# ① API Key 方式
curl -X POST http://localhost:8080/<平台侧路径> \
  -H 'Content-Type: application/json' \
  -H 'X-Client-Id: <调用方标识，如 DEMO-CLIENT>' \
  -H 'X-Api-Key: <凭证明文（在「调用方管理 → 凭证」生成时只显示一次）>' \
  -d '<请求体>'

# ② HMAC 方式：sig = hex( HMAC-SHA256( secret, "<秒级时间戳>" + "." + 原始报文 ) ) —— 与供应商回调验签同一口径
curl -X POST http://localhost:8080/<平台侧路径> \
  -H 'Content-Type: application/json' \
  -H 'X-Client-Id: DEMO-CLIENT' -H 'X-Timestamp: 1758230400' -H 'X-Signature: <sig>' \
  -d '<请求体>'
```

> 鉴权失败时 HTTP 统一 `401`，`msg` 说明原因：`40107` 未声明主体 / 主体不存在或停用、`40108` 未配置鉴权方式或无可用凭证、
> `40100` 凭证或签名不匹配、`40101` 时间戳超容差、`40103` 来源 IP 被拒。
> **排查三步**：① 看响应 `msg` 里的错误码 → ② 到「接口监控 → 接入鉴权」按主体/IP 筛审计（能看到方式与失败原因）→
> ③ 按审计行的 `traceId` 跳「调用日志」看这次调用实际发了什么。

### 9.2 供应商回调（Flow B）

```bash
curl -X POST http://localhost:8080/<回调平台路径> \
  -H 'Content-Type: application/json' \
  -H 'X-Timestamp: <秒级时间戳>' \
  -H 'X-Partner-Signature: <HMAC 签名>' \
  -d '<回调报文>'
```

### 9.3 管理面常用端点

| 操作 | 方法 + 路径 |
|---|---|
| 应用列表 / 详情 | `GET /api/admin/apps` · `GET /api/admin/apps/{appId}` |
| 应用启停 | `POST /api/admin/apps/{appId}/enable` · `.../disable` · `.../cancel` |
| 应用删除 | `DELETE /api/admin/apps/{appId}` |
| 凭证遮显列表 | `GET /api/admin/apps/{appId}/credentials` |
| 分组列表 / 创建 | `GET /api/admin/groups` · `POST /api/admin/groups` |
| 分组更新 / 删除 | `PUT /api/admin/groups/{id}` · `DELETE /api/admin/groups/{id}` |
| 接口列表 / 详情 | `GET /api/admin/interfaces?appId=&ifType=&status=&keyword=` · `GET /api/admin/interfaces/{id}` |
| 接口创建 / 更新 | `POST /api/admin/interfaces` · `PUT /api/admin/interfaces/{id}`（`X-Change-Note`） |
| 接口发布 / 下线 | `POST /api/admin/interfaces/{id}/publish` · `.../offline` |
| 接口删除 | `DELETE /api/admin/interfaces/{id}` |
| 接口测试 | `POST /api/admin/interfaces/{id}/test` |
| 模拟回调 | `POST /api/admin/interfaces/{id}/test-callback` |
| 版本列表 / 详情 | `GET /api/admin/interfaces/{id}/versions` · `.../versions/{version}` |
| 回滚 / 复制 | `POST /api/admin/interfaces/{id}/rollback` · `.../copy` |
| 适配器 | `GET /api/admin/adapters` · `GET /api/admin/adapters/impls` |
| 导入种子 | `POST /api/admin/seed/import` |
| 监控统计 | `GET /api/admin/monitor/overview` · `.../stats/trend` · `.../stats/top-interfaces` |
| 调用日志 | `GET /api/admin/monitor/call-logs` · `GET /api/admin/monitor/call-logs/{id}` |
| 出站状态链 / 对账 | `GET /api/admin/monitor/outbound-requests` · `.../{id}` · `.../{id}/audits` · `POST .../{id}/reconcile` |
| 死信 / 重放 | `GET /api/admin/monitor/dead-letters` · `POST /api/admin/monitor/dead-letters/{id}/replay` |
| 告警 / 规则 | `GET /api/admin/monitor/alerts` · `GET/POST /api/admin/monitor/alert-rules` · `PUT/DELETE .../alert-rules/{id}` |

---

## 10. 开发与测试

### 10.1 运行测试

```bash
mvn test            # 全库 290 个 @Test（集成测试连开发库；成本高时用 -Dtest=<类> 跑针对性批次）
mvn clean test      # 结构变更后务必 clean（旧 class 残留会被 Spring 扫描）
```

> ⚠️ 集成测试与手动验收共用 WireMock 端口 18080，两者不要同时运行。

### 10.2 前端构建与测试

```bash
cd frontend
npm run dev         # 开发 :5173
npm run build       # 产物 → src/main/resources/static/（后端 serve）
npm test            # 单测 83 例 + 组件 SSR 冒烟 18 例（Node 内置 test runner，无需联网）
npm run lint        # ESLint（flat config，--max-warnings 0）
```

> 改动报文美化 / 组件绑定逻辑后**必须**跑 `npm test`：`<script setup>` 里的 computed / watcher 在 JS 中需手动 `.value`，漏写会让界面恒显占位符，靠 SSR 冒烟用例兜住。

### 10.3 数据重置

`src/main/resources/doc/reset-dev.sql` 提供开发库一键重置，脚本分两段：

- **「一、运行数据」7 张**（`outbound_request` / `inbound_delivery` / `dead_letter` / `call_log` / `reconcile_audit` / `alert_event` / `outbound_request_state_log`）——日常每轮测试只想清调用痕迹时，**手动执行这一段**；
- **「二、配置数据」11 张**（应用 / 分组 / 接口及其子表 / 凭证 / 快照 / 告警规则）——重头再来才执行；`adapter` 表**保留不重置**。

```bash
# 整文件执行 = 两段都清（重头开始，自增 ID 归 1）
mysql -h <host> -u <user> -p apicenter < src/main/resources/doc/reset-dev.sql
```

重置后重启后端（空库启动不再自动导入）；需要基线时 `POST /api/admin/seed/import`（幂等，重建应用 / 凭证 / 接口）。

> 不想重置自增 ID 时，用脚本「附录 A」的 `DELETE FROM` 等价格式替换 `TRUNCATE`。

> ⚠️ 仅限开发 / 测试库；执行前确认 `SELECT DATABASE()` 返回 `apicenter`，并停掉后端与 WireMock。

### 10.4 完整验收路径

- 快速冒烟：`开发文档/测试/整体测试方案.md` §1.5「快速执行速通」（约 20 分钟，界面引导）。
- 全量回归：`整体测试方案.md` §4–§8 + 回归矩阵 R0–R6。
- 里程碑专项：`M2/M3/M4/M5手动验收测试方案.md`。

---

## 10.5 登录与账号（2026-09-18）

管理面已启用账号登录：**只做认证，不做权限**（没有角色/菜单裁剪，登录后即可用全部功能）。
登录后令牌放在浏览器本地，所有 `/api/admin/**` 请求自动带 `Authorization: Bearer <token>`。

### 10.5.1 首次使用（创建管理员账号）

1. 打开管理面（`http://localhost:5173` 或 `http://localhost:8080`）→ 未登录会自动跳到 `/login`；
2. 系统还没有任何账号时，登录页会提示「首次使用」并**默认切到「注册」**；
3. 填用户名（3-32 位小写字母/数字/`_` `.` `-`）+ 密码（8-64 位且含字母与数字）→「注册并进入」→ 直接进管理面。

### 10.5.2 日常使用

| 操作 | 位置 |
|---|---|
| 登录 / 注册 | 登录页（`/login`，已登录访问会自动回概览） |
| 修改密码 | 顶部栏右侧「账号名 ▾ → 修改密码」（成功后**其他设备的登录立即失效**，当前会话保留） |
| 退出登录 | 顶部栏右侧「账号名 ▾ → 退出登录」（二次确认；令牌即时删除，不等过期） |

会话有效期 12 小时（使用中惰性续期）；连续输错密码 5 次会锁定 5 分钟（锁定期内即使密码正确也拒绝）。

### 10.5.3 命令行 / 脚本取令牌

```bash
BASE=http://localhost:8080/api/admin

# 登录（已有账号）——首次可用 /auth/register，返回结构相同
TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Passw0rd"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')

curl -s $BASE/apps -H "Authorization: Bearer $TOKEN"      # 之后所有管理面请求都带这个头
curl -s $BASE/auth/me  -H "Authorization: Bearer $TOKEN"  # 当前账号
curl -s -X POST $BASE/auth/logout -H "Authorization: Bearer $TOKEN"   # 退出（令牌即时失效）
```

> **本文档后续所有 `curl ... /api/admin/...` 示例都需带上 `-H "Authorization: Bearer $TOKEN"`**（HTTP 401 + `40104` = 未登录/令牌过期）。
> 需要临时关闭认证（本地调试/应急）：`app.api-center.auth.enabled=false`（配置项，改后重启；生产不要关）。
> 设计细节（表结构 / 令牌口径 / 错误码 / 未做项）见《开发文档/设计/账号登录设计方案.md》。

### 10.5.4 账号管理（页面）

侧边栏「**账号管理**」（`/users`）——v1 **没有权限分级**，任何已登录账号都能管理账号，页面顶部有醒目提示。

| 操作 | 说明 |
|---|---|
| 列表 | 用户名 / 显示名 / 状态 / **有效会话数** / 最近登录 / 锁定期（剩余分钟）/ 创建时间；自己那行标注「当前账号」 |
| 新建账号 | 「＋ 新建账号」→ 用户名 + 初始密码 + 显示名（新账号**不会**自动登录，请把初始密码告知本人） |
| 编辑 | 改显示名；改状态（停用 → **该账号已登录的会话立即失效**，且无法再登录） |
| 重置密码 | 只能重置**他人**；重置后该账号所有会话立即失效（重置自己的口令请用右上角「修改密码」） |
| 解锁 | 连续失败被锁定的账号可一键解锁（清失败计数与锁定时间） |
| 删除 | 需**输入用户名确认**，不可恢复；账号与其全部会话一并删除 |

**角色（RBAC 第一层，2026-09-18）**：

| 角色 | 管理面读写 | 账号管理 | 删除账号 / 变更角色 |
|---|---|---|---|
| **OWNER** 拥有者 | 全部 | 全部 | 可以 |
| **ADMIN** 管理员 | 全部 | 新建（只能建只读）/ 启停用 / 重置口令 / 解锁 / 改显示名 | 不可 |
| **VIEWER** 只读 | **只读**（能看不能改；个人改口令与退出不受限） | 不可（菜单里看不到「账号管理」） | 不可 |

- **首个账号自动是 OWNER**（迁移已把你的 `admin` 提升为 OWNER）；之后注册得到的都是 `VIEWER`，需要管理员在账号管理里调整。
- **角色变更会吊销该账号所有会话**（避免旧权限残留），重新登录即生效。
- 越权请求由服务端直接拒：`40302` 只读不能写、`40303` 无账号管理权限。

**三条安全底线（服务端强制）**：① 不能停用/删除**最后一个可用账号**；② 不能停用/删除**当前登录的自己**、不能用「重置密码」改自己的口令；
③ 不能降级/停用/删除**最后一个 OWNER**，也不能**修改自己的角色**。违反返回 `40001`/`40303` 并给出原因；界面对应按钮置灰。

> 权限矩阵与强制位置（只有过滤器与账号服务两处）见《开发文档/设计/账号登录设计方案.md》§14。

---

## 11. 常见问题 FAQ

**Q0.1：调用 fastmoss 返回 `{"code":1002,"msg":"invalid client_secret"}`？**

这不是平台故障，是 **FastMoss 侧业务拒绝：密钥无效**。判断依据（三步取证）：

```bash
# ① 看平台持有的凭证是不是还是 seed 占位值（只回尾 4，不回显明文）
curl -s http://localhost:8080/api/admin/apps/fastmoss | grep -o '"fingerprint":"[^"]*"'
#    期望：换过真 token 后尾 4 会变；若仍是 "oken" = 还是 fastmoss-test-token 占位值

# ② 看上游真实响应（列表已瘦身，body 走详情）
curl -s 'http://localhost:8080/api/admin/monitor/call-logs?direction=OUT&pageSize=1'   # 取 id
curl -s http://localhost:8080/api/admin/monitor/call-logs/<id>      # respBody 里就是 FastMoss 的原文
#    典型：{"code":1002,"data":null,"message":"invalid client_secret",...}

# ③ 绕过平台直连，二分定位（平台配置 or FastMoss 侧）
curl -s -X POST 'https://openapi.fastmoss.com/shop/v1/creatorList' \
  -H 'Content-Type: application/json' -H "Authorization: Bearer <你的真 token>" \
  -d '{"filter":{"seller_id":"7494312521977267257"},"page":1,"pagesize":1}'
```

- ③ 也报 1002 → **token 本身无效/未开通/环境不对**（去 FastMoss 控制台确认 API 已开通、token 未过期、账号有该接口权限）；
- ③ 正常但平台报 1002 → 平台凭证没更新成功：应用管理 → `fastmoss` → 编辑 → 凭证卡片填真 token 保存（或 `POST /api/admin/apps/fastmoss/credentials/update` body `{"kind":"OUTBOUND","credential":"<真 token>"}`）。
  **凭证每请求实时读，无需重启**；用 ① 复核指纹已变。

> 权威依据（FastMoss 官方「快速开始」developers.fastmoss.com）：API Key 即控制台里创建的 **`client_secret`**，
> 以 **`Authorization: Bearer <client_secret>`** 发送；baseUrl `https://openapi.fastmoss.com`；
> **成功 = HTTP 200 且业务 `Code` = 0**（与平台 `ADP-201` 的信封配置 `codeField=code / successValue=0` 完全对应）。
> 所以 seed 的 `ADP-101`（`headerName=Authorization` / `prefix=Bearer`）**本身就是对的**，只需把凭证值换成真 `client_secret`。
> 另：`prefix` 置空 = 发送**裸 token（无前导空格，2026-09-18 起）**，这是给「要求裸 token / 自定义头」的其他供应商留的通用旋钮；
> 业务失败（如 1002）按设计记 `SUCCESS` 并透传业务码 —— 排查要看响应体（`call-logs/{id}` 的 `respBody`），不要只看状态机状态。

**Q-1：调管理面接口返回 `401` / `{"code":40104,...}`？**

管理面已启用账号登录（2026-09-18）：未登录或令牌过期。三种处置：

- 界面：会自动跳到登录页，登录后原路返回；
- 命令行：先按 §10.5.3 登录取 `TOKEN`，再带 `-H "Authorization: Bearer $TOKEN"`；
- 确认不是认证问题（例如脚本/集成环境临时使用）：`app.api-center.auth.enabled=false` 关闭校验后重启。

其他账号类错误码：`40105` 用户名或密码错误（登录失败 5 次会 `40106` 锁定 5 分钟）、`40301` 注册已关闭、`40901` 用户名已存在、`40001` 用户名/密码不符合规则。

**Q0：点「保存 / 测试接口」报 `Invalid CORS request`（HTTP 403）？**

不是业务故障，是**跨域来源未被允许**——请求在 CORS 层就被拒了（不进引擎，所以监控里看不到 call_log）。
最常见触发：用 **`http://127.0.0.1:5173`** 打开前端（`127.0.0.1` 与 `localhost` 是不同 Origin），
或 5173 被占用后 Vite 自动改用 **5174**。只有 POST/PUT/DELETE 会带 Origin，所以看起来「只有写操作报错」。

2026-09-18 起默认已改为**本机回环任意端口**（配置项 `app.api-center.cors.allowed-origin-patterns`，
默认 `http://localhost:[*],http://127.0.0.1:[*]`）。若仍报错：
- 后端未重启 → 重启加载新配置；
- 用局域网 IP / 域名访问 → 把该 Origin 加进上面这个配置项（逗号分隔），或改用 `localhost`；
- 生产同源部署（静态资源由本服务提供）或反向代理**无需**该配置（置空即可）。

**Q1：调用返回 `40102 应用未启用`？**
应用处于 DRAFT / DISABLED。到「应用管理」点「启用」（ENABLED 才可路由）。

**Q2：调用返回 `40401 接口不存在 / 接口未发布`？**
平台侧路径不匹配（注意全局唯一、含前导 `/`），或接口未发布。草稿态可用「测试接口」调试，但网关不路由。

**Q3：返回 `40002 报文超过大小限制`？**
默认上限 1MB（`app.api-center.max-body-bytes`），出站请求与入站回调双向生效。

**Q4：返回 `42901 / 42902 / 40103`？**
接入层防护：QPS 限流 / 日配额超限 / IP 名单拒绝。这三类拒绝**不落运行表**，只落 `call_log`；调整应用上的限流与名单配置即可。

**Q5：返回 `50201` 并提示「已进入补偿队列 / 死信编号 N」？**
供应商 5xx/429 重试耗尽转补偿；4xx（非 429）直接死信。到「接口监控 → 状态机与对账 / 死信」查看与处理。

**Q6：返回 `50401 待对账 UNKNOWN`？**
供应商读超时，结果不确定。到监控页对该请求人工置位（已到达 / 未到达），或等 TTL 10 分钟自动降级。

**Q7：返回 `50202 熔断短路`？**
该「接口 + 供应商」在滑动窗口内失败率超阈值，熔断器 OPEN。冷却 30s 后半开探测，恢复后补偿 worker 补做；已入队记录**不会转死信**。

**Q8：配置改了但调用没生效？**
链装配时烘焙了绑定 / 映射规则（凭证仍实时读），依赖 `ConfigChangedEvent` 即时失效。正常情况保存即生效；若走的是历史遗留入口漏发事件，TTL 兜底最长 5 分钟。改接口用 `PUT + X-Change-Note`，改完在监控页确认。

**Q9：`mvn test` 报 Bean 重复 / 找不到类？**
结构变更（删源文件、改包名）后旧 class 残留 `target/classes` 会被 Spring 扫描，务必 `mvn clean test`。

**Q10：入站回调返回 ack 成功，但送达状态是 PENDING？**
正常。ack 与送达解耦：收到即回 ack，送达失败由补偿 worker 按 `callback_url_snapshot` 重送。确认回调地址可达（本地需 `callback-allow-private: true`）。

**Q11：回调验签失败 `40100` / `40101`？**
`40100` = 缺 `X-Timestamp` / 缺 `X-Partner-Signature` 头、时间戳非法、签名不匹配、或防重放命中；`40101` = 时间戳超出容差（默认 300s，调 `timestampToleranceSeconds`）。逐一排查：① 签名串是否为 `timestamp + "." + body`（**漏英文句点是最常见原因**，见 §5.2）；② CALLBACK 凭证是否为 ACTIVE；③ 发请求机器与平台时钟是否偏差过大。

**Q12：本地回调地址被拒绝？**
运行参数 `app.api-center.callback-allow-private` 需为 `true`（开发 / 测试指向本地 WireMock）；生产必须 `false`。

**Q13：想清空演示数据从头再来？**
执行 `reset-dev.sql`（见 §10.3）后重启，再按需 `POST /api/admin/seed/import`。

**Q14：数据库 / 密钥生产怎么配？**
DB 密码用环境变量 `${APICENTER_DB_PASSWORD}`；AES 密钥用 `APICENTER_CRYPTO_KEY`（缺失启动失败，拒绝明文裸跑）。回调地址私网开关 `callback-allow-private` 与 `trust-xff` 按部署环境设置。

---

## 附：关键运行参数（`application.yaml`）

| 参数 | 默认 | 说明 |
|---|---|---|
| `app.api-center.seed.enabled` | `false` | 种子自动导入开关 |
| `app.api-center.retry-worker-fixed-delay-ms` | `3000` | 补偿 worker 扫描间隔 |
| `app.api-center.max-body-bytes` | `1048576` | 报文大小上限（1MB） |
| `app.api-center.connect-timeout-ms` | `3000` | 出站客户端**全局连接超时**（per-request 连接超时需 per-request HttpClient，成本高收益低） |
| `app.api-center.client-auth.mode` | `OFF` | **调用方鉴权灰度三态**：`OFF` 不校验（现状，**仍写审计**）/ `OPTIONAL` 有凭证就验 / `ENFORCED` 必须通过（fail-closed）。上线路径建议 OFF+审计 → OPTIONAL → ENFORCED |
| `app.api-center.client-auth.id-header` | `X-Client-Id` | 主体标识头名（单个调用方可在其适配器 params 里覆盖） |
| `app.api-center.default-read-timeout-ms` | `10000` | **兜底读超时**：接口未配 `timeout_ms` / 配置非法（≤0）/ 未声明作用域（如管理面自调）时使用 |

> 接口级配置值域（保存校验，越界返回 40001）：**读超时 100~60000ms**（默认 **10000**，2026-09-22 起；此前 3000）、**最大重试 0~10**（默认 4）。
| `app.api-center.callback-allow-private` | `true`（开发） | 回调地址允许内网（生产须 `false`） |
| `app.api-center.unknown-ttl-minutes` | `10` | UNKNOWN 自动降级时长 |
| `app.api-center.circuit.*` | 见 yaml | 熔断三态参数（阈值 / 窗口 / 冷却 / 半开探测） |
| `app.api-center.alert-worker-fixed-delay-ms` | `30000` | 告警评估间隔 |
| `app.api-center.verify-fail-alert-threshold` | `10` | 验签连续失败告警阈值（5 分钟窗口） |
| `app.api-center.trust-xff` | `false` | 是否信任 `X-Forwarded-For` 首值取来源 IP |
| `app.api-center.crypto.key` | 开发占位 | 凭证 AES-256-GCM 密钥（生产用环境变量） |
