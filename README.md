# apicenter · 项目索引

API 三方接口统一调用平台组件 —— 只做 **连接 + 适配 + 可靠传输**，不承接业务决策。

> **两代设计并存，先分清对象**：
> - **现行设计 =「API 中心」**：`src/main/resources/doc/` 6 份文档 —— 通用平台形态（应用=供应商，5 模块）；实现尚未落地（排期见《可行性报告.md》）。
> - **现有代码 = 旧版 demo**：`src/main/resources/doc_old/` 设计文档 + Java 代码 ——「ERP 订单连接器」（PARTNER_A / PARTNER_B 固定渠道），当前可运行。

## 现行设计（API 中心）

### 定位

基础 API 中转 + 字段映射的统一调用平台：应用（供应商）/ 分组 / 接口 / 监控 / 适配器 5 个功能模块；支持 JSON / XML 双协议、多鉴权（含云厂商签名与回调验签）、接口级字段映射、可靠传输（重试 / 补偿 / 死信 / 对账 / 熔断）、全链路可观测。

### 两条核心链路

1. **Flow A 出站（调用方 → 平台 → 供应商）**：适配器链（入站鉴权 → 协议解码 → 报文适配 → 字段映射 → 协议编码 → 出站鉴权=供应商签名）→ 调供应商 → 反向适配回调用方。失败按状态机处理：5xx/429 指数退避短重试 → 补偿；4xx → 死信；超时 → UNKNOWN 对账。
2. **Flow B 入站回调（供应商回调 → 平台 → 调用方）**：回调验签（凭证独立于出站签名）→ 适配器链 → 送达回调地址 → 收到即回 ack 回执（与送达解耦）→ 送达失败由补偿 worker 重送。

### 文档导航

| 交付物 | 路径 | 说明 |
|---|---|---|
| 设计总纲 | [API中心设计方案.md](src/main/resources/doc/API中心设计方案.md) | 5 模块；接口定义模型（出站中转 / 入站回调）；三类适配器（鉴权 / 协议 / 报文）+ 接口级字段映射；状态机 / 错误码 / 容错附录 |
| 实现方案 | [技术架构和实现方案.md](src/main/resources/doc/技术架构和实现方案.md) | 分层架构、技术选型、适配器链引擎、出 / 入站执行引擎、M1–M5 路线图、ADR |
| 可行性报告 | [可行性报告.md](src/main/resources/doc/可行性报告.md) | 技术可行性评估、工作量估算（约 88 人日）、风险与应对 |
| 表结构设计 | [表结构设计.html](src/main/resources/doc/表结构设计.html) | 15 张表（配置 10 + 运行 5）+ 枚举汇总 + 原型数据模型映射对照 |
| 时序与流程 | [API中心时序图与流程图.md](src/main/resources/doc/API中心时序图与流程图.md) | 配置流程、Flow A / B 时序、请求处理 + 容错流程图 |
| 交互原型 | [API中心原型.html](src/main/resources/doc/API中心原型.html) | 可交互管理面原型（数据模型与交互即事实来源） |

> 两代文档勿混用：改 doc/ 下文档时不参照 doc_old / schema.sql / 旧 Java 类名；旧 demo 9 张表与现行设计 15 张新表不是同一套。

## 旧版 Demo（现有代码）

> 以下内容描述现有 Java 代码——旧版 ERP 订单连接器 demo（按 `doc_old/设计文档.md` 12 章实现）。逻辑上是 ERP 的「小组件」，物理上是独立 Spring Boot 服务、独立库表、独立发布。

### 两条核心链路

1. **Flow A 出站（ERP → 组件 → 第三方）**：ERP 调组件统一接口 `POST /api/orders` 推送订单 → 组件验签 → MapStruct 字段映射（同一订单映射为 PARTNER_A JSON / PARTNER_B XML）→ `@HttpExchange` 调用第三方 → 响应反向映射返回 ERP。失败按状态机处理：500/429 指数退避短重试 → 持久化补偿 → 成功；400 → 死信；超时 → UNKNOWN 对账。
2. **Flow B 入站回调（第三方 → 组件 → ERP）**：ERP 在组件登记回调订阅 → 第三方回调 `POST /callback/{channel}/order-status` → 组件验签 → 映射为 ERP 事件 → 送达 ERP 回调 URL → ERP ack → 组件回第三方 ack。送达失败由补偿 worker 重发。

### 四大能力

| 能力 | 说明 | 实现 |
|---|---|---|
| 3.1 字段映射 | 同一业务对象映射为 JSON / XML 两种第三方格式 | `@HttpExchange` + Jackson 注解 + MapStruct |
| 3.2 失败重试 | 500/429 短重试（指数退避）、持久化补偿、死信、超时对账 | Spring 7 `@Retryable` + 补偿 worker |
| 3.3 请求日志 | AOP 统一拦截、traceId 全链路串联、敏感头脱敏 | `@Aspect` + OpenTelemetry |
| 3.4 定时/实时同步 | 定时增量拉取（高水位游标）+ 同窗口防重 | `@Scheduled`（Demo）/ XXL-JOB（生产） |

### 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders` | Flow A 出站：推送订单（Header：`X-App-Id` / `X-Timestamp` / `X-Signature`） |
| POST | `/api/orders/query` | UNKNOWN 对账查询 |
| POST | `/callback/{channel}/order-status` | Flow B 入站：第三方回调（Header：`X-Partner-Signature` / `X-Timestamp`） |

### 技术栈与运行

Java 21 · Spring Boot 4.1 · Spring Framework 7 · Jackson 3 · MapStruct 1.6.3 · H2（Demo）· OpenTelemetry

```bash
mvn spring-boot:run      # 启动后端 :8080
```
- `/` → Vue3 前端（真实调用后端接口）
- `/h2-console` → H2 控制台
- 静态演示页已迁至 `doc_old/api演示 demo.html`（浏览器直接打开，交互演示两条链路与四大能力）

> 接口 / 状态机 / 字段映射细节见 [设计文档.md](src/main/resources/doc_old/设计文档.md)。

## 旧版文档导航

| 交付物 | 路径 | 说明 |
|---|---|---|
| 设计文档 | [设计文档.md](src/main/resources/doc_old/设计文档.md) | 12 章：定位 / 架构 / 两条链路时序 / 字段映射 / 重试补偿状态机 / 日志链路 / 定时同步 / 数据模型 / API / 验收映射 |
| 实现指南 | [实现指南.md](src/main/resources/doc_old/实现指南.md) | 各能力「如何实现」：调用第三方 / 字段映射 / 失败重试 / 请求日志读写 / 定时同步 / 回调配置·调用·返回值映射，含代码位置 |
| 建表脚本 | [schema.sql](src/main/resources/doc_old/schema.sql) | 9 张表（H2 语法，设计映射 MySQL 8 生产） |
| Demo 页面 | [api演示 demo.html](src/main/resources/doc_old/api演示%20demo.html) | 独立静态 HTML（浏览器直接打开），交互演示两条链路与四大能力 |

## Demo 页面动线（旧版）

1. **Flow A 出站**（ERP → 组件 → 第三方）：点击「▶ 逐步调用」，切换右上角场景（纯成功 200 / 重试 / 重试耗尽补偿 / 429 / 400 死信 / 超时对账），底部日志控制台按 span 着色。
2. **Flow B 入站回调**（第三方 → 组件 → ERP）：勾选「模拟 ERP 暂不可用」可看送达失败 → 补偿 worker 重发分支。
3. **字段映射**：悬停表格行，联动高亮 JSON / XML 样例中的对应字段（金额元→分、时间格式、嵌套聚合）。
4. **定时增量拉取**：点「触发一次拉取」，看高水位游标推进 + 同窗口防重（dedup）。
5. **状态机 · 数据模型**：运行 Flow A / Flow B 时当前状态实时高亮。

> 全页顶部共享同一 `traceId`（W3C traceparent），一次业务从 UI 到第三方全程串联。

## 四大能力 ↔ 章节 ↔ Demo 对照（旧版）

| 能力 | 设计文档章节 | Demo 位置 |
|---|---|---|
| 3.1 字段映射 | §5 | Tab「字段映射」+ Flow A 步骤③/⑥ |
| 3.2 失败重试 | §6 | Tab「Flow A」场景下拉（纯成功/重试/补偿/429/400/超时） |
| 3.3 请求日志 | §7 | 顶部 traceId 栏 + 底部日志控制台（敏感头脱敏） |
| 3.4 定时/实时同步 | §8 | Tab「定时增量拉取」 |

## 事实来源

- **现行设计**：`src/main/resources/doc/` 六份文档（设计方案为总纲，表结构 / 实现方案 / 排期配套）
- **旧版 demo**：
  - `src/main/resources/application.yaml` — 渠道（PARTNER_A / PARTNER_B / ERP）、`max-attempts`、`retry-worker-fixed-delay-ms`、`signature-tolerance-seconds`、`read-timeout-ms`
  - `pom.xml` — Spring Boot 4.1 / Java 21 / MapStruct 1.6.3 / WireMock 3.9.1 / `jackson-dataformat-xml`
  - `src/main/java/com/deepx/apicenter/ApicenterApplication.java` — `@EnableResilientMethods` / `@EnableScheduling`
