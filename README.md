# apicenter · 设计文档与 Demo 索引

API 三方接口统一调用平台组件 —— 逻辑上是 ERP 的「小组件」，物理上是独立 Spring Boot 服务、独立库表、独立发布；只做 **连接 + 适配 + 可靠传输**，不承接 ERP 业务决策。

## 项目功能

### 定位

独立 Spring Boot 服务，作为 ERP 与第三方系统的「连接器」：对外向 ERP 暴露统一、稳定的调用面；对内做字段适配、失败重试、可靠传输、全链路可观测。**只做「连接 + 适配 + 可靠传输」，不承接 ERP 业务决策。**

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
- `/api演示 demo.html` → 静态交互演示页
- `/doc/设计文档.md` → 设计文档

> 接口 / 状态机 / 字段映射细节见 [设计文档.md](src/main/resources/doc/设计文档.md)。

## 文档导航

| 交付物 | 路径                                                        | 说明 |
|---|-------------------------------------------------------------|---|
| 设计文档 | [设计文档.md](src/main/resources/doc/设计文档.md)           | 12 章：定位 / 架构 / 两条链路时序 / 字段映射 / 重试补偿状态机 / 日志链路 / 定时同步 / 数据模型 / API / 验收映射 |
| 实现指南 | [实现指南.md](src/main/resources/doc/实现指南.md)           | 各能力「如何实现」：调用第三方 / 字段映射 / 失败重试 / 请求日志读写 / 定时同步 / 回调配置·调用·返回值映射，含代码位置 |
| Demo 页面 | [api演示 demo.html](src/main/resources/api演示%20demo.html) | 独立静态 HTML（浏览器直接打开），交互演示两条链路与四大能力 |

## Demo 页面动线

1. **Flow A 出站**（ERP → 组件 → 第三方）：点击「▶ 逐步调用」，切换右上角场景（纯成功 200 / 重试 / 重试耗尽补偿 / 429 / 400 死信 / 超时对账），底部日志控制台按 span 着色。
2. **Flow B 入站回调**（第三方 → 组件 → ERP）：勾选「模拟 ERP 暂不可用」可看送达失败 → 补偿 worker 重发分支。
3. **字段映射**：悬停表格行，联动高亮 JSON / XML 样例中的对应字段（金额元→分、时间格式、嵌套聚合）。
4. **定时增量拉取**：点「触发一次拉取」，看高水位游标推进 + 同窗口防重（dedup）。
5. **状态机 · 数据模型**：运行 Flow A / Flow B 时当前状态实时高亮。

> 全页顶部共享同一 `traceId`（W3C traceparent），一次业务从 UI 到第三方全程串联。

## 四大能力 ↔ 章节 ↔ Demo 对照

| 能力 | 设计文档章节 | Demo 位置 |
|---|---|---|
| 3.1 字段映射 | §5 | Tab「字段映射」+ Flow A 步骤③/⑥ |
| 3.2 失败重试 | §6 | Tab「Flow A」场景下拉（纯成功/重试/补偿/429/400/超时） |
| 3.3 请求日志 | §7 | 顶部 traceId 栏 + 底部日志控制台（敏感头脱敏） |
| 3.4 定时/实时同步 | §8 | Tab「定时增量拉取」 |

## 事实来源

- `src/main/resources/application.yaml` — 渠道（PARTNER_A / PARTNER_B / ERP）、`max-attempts`、`retry-worker-fixed-delay-ms`、`signature-tolerance-seconds`、`read-timeout-ms`
- `pom.xml` — Spring Boot 4.1 / Java 21 / MapStruct 1.6.3 / WireMock 3.9.1；后续实现需新增 `jackson-dataformat-xml`
- `src/main/java/com/deepx/apicenter/ApicenterApplication.java` — `@EnableResilientMethods` / `@EnableScheduling`
