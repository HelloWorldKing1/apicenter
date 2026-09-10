# apicenter · 使用教程

> 版本：v1.0（2026-09-09）｜ 配套：[《API中心项目说明》](API中心项目说明.md) ｜ [README](../../../../README.md)
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
8. [监控与容错运维](#8-监控与容错运维)
9. [调用速查（curl）](#9-调用速查curl)
10. [开发与测试](#10-开发与测试)
11. [常见问题 FAQ](#11-常见问题-faq)

---

## 1. 环境准备

### 1.1 必需组件

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | **21**（推荐，工程 `--release 21`） | 本仓库 `.java-version` = 21 |
| Maven | 3.9+ | ⚠️ 仓库**无** `mvnw`，需系统安装（机器已有 `apache-maven-3.9.16` 可直接用） |
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

数据库已按 `src/main/resources/doc/schema.sql` 建好（19 张表）。首次在全新库上部署时：

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
| 应用标识 `appId` | 全局唯一，创建后作为定位键 |
| 服务地址 `baseUrl` | 供应商根地址，与接口的「供应商接口路径」拼接 |
| 出站鉴权适配器 `authAdapterId` | 应用级默认出站签名适配器（接口级可覆盖） |
| 回调验签适配器 `callbackAuthAdapterId` | 应用级默认回调验签适配器（仅 INBOUND 生效） |
| 默认报文适配器 `defaultMessageAdapterId` | 应用级默认报文适配器 |
| IP 白名单 / 黑名单 | 接入层来源控制，命中拒绝 `40103` |
| QPS 上限 / 日配额 | 接入层限流，超限 `42901` / `42902` |

生命周期：`DRAFT → ENABLED → DISABLED → CANCELLED`。

- `enable`：仅 DRAFT / DISABLED 可启用；
- `disable`：仅 ENABLED 可停用；
- `cancel`：仅 DISABLED 可注销。

### 4.2 凭证管理

每个应用下有两类独立凭证：`OUTBOUND`（出站供应商签名）、`CALLBACK`（回调验签）。**管理面永不回显明文**，只显示尾 4 位指纹。

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
- 凭证类参数（如 `apiKey` / `token` / `secretKey`）**不落适配器 params**，统一在应用凭证管理中维护。
- 常用做法：优先使用 seed 提供的通用适配器实例；确需定制时新建，`adapter.name` 必须全表唯一。

### 4.4 接口管理

**请求参数（Params）**

- `side = IN`：入站侧（调用方 / 供应商回调进来的字段）；
- `side = OUT`：出站侧（转发给供应商 / 送达给回调地址的字段）；
- 字段：名称、类型、是否必填、示例值、排序。

**请求体（Bodies）**

- `side = IN`：入站请求体（json / form / xml / raw）；
- `side = OUT`：出站转发方式（透传 / 模板）。

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
# 签名 = HMAC-SHA256(secret, timestamp + body)，具体实现见 HmacSigner
SIG=<计算出的签名>

curl -i -X POST http://localhost:8080/qd/callback \
  -H 'Content-Type: application/json' \
  -H "X-Timestamp: $TS" \
  -H "X-Partner-Signature: $SIG" \
  -d "$BODY"
```

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

```bash
curl -X POST http://localhost:8080/api/admin/interfaces/<id>/test \
  -H 'Content-Type: application/json' \
  -d '{"hello":"world"}'
```

响应 data 为 `{chainTrace, result}`：

- `chainTrace`：本次装配出的适配器链（role / adapterId / impl / version），test 端点**强制实时解析**，永远反映当前配置；
- `result`：原业务结果。

> 注意：调试端点有意**不落 IN 条 `call_log`**，避免调试流量污染成功率口径；OUT 条照常落库。

### 6.2 模拟回调（入站调试）

「接口管理」详情 →「模拟回调」（仅 INBOUND，且接口必须已发布）：按应用 `CALLBACK` 凭证自动签名，自调平台真实网关路径，返回：

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
curl "http://localhost:8080/api/admin/monitor/call-logs?direction=OUT&appId=QD1&statusGroup=5xx&page=1&pageSize=20"
```

支持按 traceId / 接口 / 方向 / 应用 / HTTP 状态（精确或 2xx-4xx-5xx 分组）/ 时间范围 / 关键字过滤。日志明细中敏感字段已脱敏（无明文密钥）。

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
| 凭证遮显列表 | `GET /api/admin/apps/{appId}/credentials` |
| 分组列表 / 创建 | `GET /api/admin/groups` · `POST /api/admin/groups` |
| 接口列表 / 详情 | `GET /api/admin/interfaces?appId=&ifType=&status=&keyword=` · `GET /api/admin/interfaces/{id}` |
| 接口创建 / 更新 | `POST /api/admin/interfaces` · `PUT /api/admin/interfaces/{id}`（`X-Change-Note`） |
| 接口发布 / 下线 | `POST /api/admin/interfaces/{id}/publish` · `.../offline` |
| 接口测试 | `POST /api/admin/interfaces/{id}/test` |
| 模拟回调 | `POST /api/admin/interfaces/{id}/test-callback` |
| 版本列表 / 详情 | `GET /api/admin/interfaces/{id}/versions` · `.../versions/{version}` |
| 回滚 / 复制 | `POST /api/admin/interfaces/{id}/rollback` · `.../copy` |
| 适配器 | `GET /api/admin/adapters` · `GET /api/admin/adapters/impls` |
| 导入种子 | `POST /api/admin/seed/import` |

---

## 10. 开发与测试

### 10.1 运行测试

```bash
mvn test            # 全库 180 个 @Test（集成测试连开发库）
mvn clean test      # 结构变更后务必 clean（旧 class 残留会被 Spring 扫描）
```

> ⚠️ 集成测试与手动验收共用 WireMock 端口 18080，两者不要同时运行。

### 10.2 前端构建

```bash
cd frontend
npm run dev         # 开发 :5173
npm run build       # 产物 → src/main/resources/static/（后端 serve）
```

### 10.3 数据重置

`src/main/resources/doc/reset-dev.sql` 提供开发库一键重置（运行数据 8 张 / 配置数据 11 张，`adapter` 表默认保留）：

```bash
mysql -h <host> -u <user> -p apicenter < src/main/resources/doc/reset-dev.sql
```

重置后重启后端（空库启动不再自动导入）；需要基线时 `POST /api/admin/seed/import`。

> ⚠️ 仅限开发 / 测试库；执行前确认 `SELECT DATABASE()` 返回 `apicenter`，并停掉后端与 WireMock。

### 10.4 完整验收路径

- 快速冒烟：`开发文档/整体测试方案.md` §1.5「快速执行速通」（约 20 分钟，界面引导）。
- 全量回归：`整体测试方案.md` §4–§8 + 回归矩阵 R0–R6。
- 里程碑专项：`M2/M3/M4/M5手动验收测试方案.md`。

---

## 11. 常见问题 FAQ

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

**Q11：回调验签失败 `40100`？**
检查 `X-Timestamp` 与 `X-Partner-Signature` 是否正确、CALLBACK 凭证是否配置为 ACTIVE、时间戳是否在容差内（默认 300s）。

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
| `app.api-center.callback-allow-private` | `true`（开发） | 回调地址允许内网（生产须 `false`） |
| `app.api-center.unknown-ttl-minutes` | `10` | UNKNOWN 自动降级时长 |
| `app.api-center.circuit.*` | 见 yaml | 熔断三态参数（阈值 / 窗口 / 冷却 / 半开探测） |
| `app.api-center.alert-worker-fixed-delay-ms` | `30000` | 告警评估间隔 |
| `app.api-center.verify-fail-alert-threshold` | `10` | 验签连续失败告警阈值（5 分钟窗口） |
| `app.api-center.trust-xff` | `false` | 是否信任 `X-Forwarded-For` 首值取来源 IP |
| `app.api-center.crypto.key` | 开发占位 | 凭证 AES-256-GCM 密钥（生产用环境变量） |
