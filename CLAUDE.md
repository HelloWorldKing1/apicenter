# CLAUDE.md

## 项目概述

**apicenter** —— API 三方接口统一调用平台组件。定位：**只做连接 + 适配 + 可靠传输，不承接业务决策。**

> **两代设计并存，先分清对象**：
> - **现行设计 = 「API 中心」**：`src/main/resources/doc/` 下 6 份文档（设计方案 / 技术架构 / 可行性报告 / 表结构设计 / 时序图 / 原型），通用平台形态（应用=供应商，5 模块）。
> - **现有代码 = 旧版 demo**：Java 代码按 `doc_old/设计文档.md` 实现，是「ERP 订单连接器」（PARTNER_A/B 固定两渠道）。现行设计尚未落地（排期见《可行性报告.md》）。

两条核心链路（两代语义一致）：

- **Flow A 出站**（调用方 → 组件 → 供应商）：入站鉴权 → 适配器链（协议解码 → 报文适配 → 字段映射 → 协议编码）→ 出站鉴权（供应商签名）→ 调供应商 → 反向适配回调用方。失败按状态机处理：5xx/429 指数退避重试 → 补偿；4xx → 死信；超时 → UNKNOWN 对账。
- **Flow B 入站回调**（供应商回调 → 组件 → 调用方）：回调验签（凭证独立于出站签名）→ 适配器链 → 送达回调地址 → 收到即回 ack 回执（与送达解耦）→ 送达失败由补偿 worker 重送。

## 设计文档（现行）

`src/main/resources/doc/`（6 份，互相引用闭环，改动需同步）：

| 文档 | 内容 |
|---|---|
| `API中心设计方案.md` | 设计总纲：应用（供应商）/ 分组 / 接口 / 监控 / 适配器 5 模块；接口定义模型（出站中转 / 入站回调）；三类适配器（鉴权 / 协议 / 报文）+ 接口级字段映射；状态机 / 错误码 / 容错附录 |
| `技术架构和实现方案.md` | 实现路径：分层架构、技术选型、适配器链引擎、出 / 入站执行引擎、M1–M5 路线图、ADR |
| `可行性报告.md` | 技术可行性评估、工作量估算（约 88 人日）、风险与应对 |
| `表结构设计.html` | 15 张表（配置 10 + 运行 5）+ 枚举汇总 + 原型数据模型映射对照 |
| `API中心时序图与流程图.md` | 配置流程、Flow A / B 时序、请求处理 + 容错流程图 |
| `API中心原型.html` | 可交互管理面原型（数据模型与交互即事实来源） |

关键设计要点（与旧版 demo 的主要差异，改动前先读设计方案对应章节）：

- **应用 = 供应商（上游）**：出站凭证（供应商签名）+ 回调验签凭证两类分离；调用方鉴权 / 向回调地址签名由平台统一，不在模型内（§1.2 / §3.1 / §5.3）。
- **接口两种类型**：出站中转（上游路径 + 供应商签名 + 出站响应字段）/ 入站回调（回调地址 + 回调验签 + 出站侧送达报文必填 + ack 回执字段）；类型互斥字段按类型清空（§3.1）。
- **适配器三类**：鉴权 / 协议 / 报文；字段映射为接口级配置（不是适配器）；协议适配器按接口协议自动推导、不参与绑定；绑定角色 = 报文 / 供应商签名 / 回调验签，应用级默认 + 接口级覆盖（§5.1 / §5.7）。
- **字段映射**：运行时规则（source/op/target/param/nullStrategy，6 种操作），非编译期映射（§5.6）。
- **无平台侧幂等开关**：去重依赖上游对业务键幂等（§6.3）。
- **入站 ack = 回执**：收到即回、与送达解耦，无「调用方 ack → 供应商 ack」反向映射（§5.5 / §6.1）。

## 现有代码（旧版 demo）

> 以下各节描述现有 Java 代码——旧版 ERP 订单连接器 demo（按 `doc_old/设计文档.md` 12 章实现）。

### 技术栈

Java 21 · Spring Boot 4.1（parent `spring-boot-starter-parent:4.1.0`）· Spring Framework 7 · Jackson 3（`tools.jackson.dataformat:jackson-dataformat-xml`）· MapStruct 1.6.3 · JdbcTemplate + MySQL（PolarDB，连接信息见 application.yaml；旧版 demo 曾用 H2）· OpenTelemetry · Micrometer/Prometheus · Lombok。

持久化无 JPA/Repository，全部为 `JdbcTemplate` 直连 SQL，DDL 见 `src/main/resources/doc_old/schema.sql`。

### 常用命令

> **注意**：仓库无 Maven wrapper（无 `mvnw` / `.mvn/`），且当前机器 `mvn` 不在 PATH，需自行安装 Maven 与 JDK 21。

```bash
mvn spring-boot:run   # 启动后端 :8080（连接 application.yaml 配置的 MySQL/PolarDB；建库脚本 doc/schema.sql 已执行）
mvn test              # 跑测试（现有仅 1 个 contextLoads 冒烟测试）
mvn package           # 打可执行 jar
```

运行后可访问：`/` → Vue3 前端；`/actuator/health` 等。

### 架构与源码结构

根包 `com.deepx.apicenter`（`src/main/java/com/deepx/apicenter/`）：

| 包 | 职责 | 关键类 |
|---|---|---|
| `controller/` | REST 入口（Flow A/B） | `OrderController`（/api）、`CallbackController`（/callback） |
| `service/` | 业务编排 | `OrderSyncService`（出站状态机）、`CallbackDeliveryService`（入站送达）、`PartnerInvoker`（@Retryable 调用）、`SignatureService`（HMAC 验签） |
| `client/` | 声明式 HTTP 客户端（`@HttpExchange`） | `PartnerAClient`（JSON）、`PartnerBClient`（XML）、`ErpCallbackClient` |
| `mapper/` | MapStruct 字段映射 | `OrderMapper`（元→分、时间格式、嵌套聚合、状态枚举→数字） |
| `dto/` | Java record 传输模型 | `OrderDto`（统一订单）、`PartnerAOrderRequest` / `PartnerBOrderRequest`、`PartnerResponse`、`ErpOrderResponse`、`OrderStatusCallbackDto` |
| `config/` | 配置与 Bean 装配 | `ChannelProperties`（`@ConfigurationProperties("app.integration")`）、`RestClientConfig`（构建各渠道 client） |
| `aspect/` | AOP | `LoggingAspect`（service 方法日志、traceId、敏感头/手机号脱敏） |
| `worker/` | 定时任务 | `CompensationWorker`（3s 扫描补偿）、`IncrementalSyncJob`（60s 高水位增量拉取） |

入口：`ApicenterApplication.java`（`@SpringBootApplication` + `@EnableScheduling` + `@EnableResilientMethods`，后者启用 Spring 7 `@Retryable`）。

### API 面

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders` | Flow A 推送订单。Header：`X-App-Id` / `X-Timestamp` / `X-Signature`。验签：`HMAC-SHA256(appId + timestamp + orderId, erp-secret)`，时间戳容差 300s，失败返回 401 |
| POST | `/api/orders/query` | UNKNOWN 对账查询（示例级占位实现） |
| POST | `/callback/{channel}/order-status` | Flow B 第三方回调。Header：`X-Partner-Signature` / `X-Timestamp`，用该渠道密钥验签，失败返回 401 `{"code":401,"msg":"signature invalid"}` |

### 核心状态机与关键设计

- **出站状态机**（`OrderSyncService.RequestStatus`，载体 `integration_request.status`）：`INIT → MAPPING → SENDING → RETRYING → COMPENSATING → SUCCESS / DEAD_LETTER / UNKNOWN`。
  - 5xx/429 → `PartnerInvoker` 的 `@Retryable` 短重试（maxRetries=4，指数退避 200ms×2，上限 2s）；重试耗尽仍失败 → 补偿
  - 4xx（非 429）→ 写 `dead_letter` → DEAD_LETTER，不重试
  - 读超时 / 连接异常 → UNKNOWN → 对账（当前示例直接置 SUCCESS）
- **`@Retryable` 必须放在独立类 `PartnerInvoker`** 中，而不是 `OrderSyncService` 内部——Spring AOP 自调用不触发代理（见 `PartnerInvoker` 类注释）。
- **入站送达状态**（`callback_delivery.delivery_status`）：RECEIVED → ERP_ACKED / PENDING（失败时仍回 ack，第三方不重发，由 `CompensationWorker` 重送）。
- **超时映射**：client 读超时（默认 3000ms，`read-timeout-ms`）触发 `ResourceAccessException` → UNKNOWN 分支。
- 所有出站 client 由 `config/RestClientConfig` 用 `HttpServiceProxyFactory` + `RestClient` 构建（Boot 4 不自动装配 `RestClient.Builder`）。

### 配置与数据模型

配置集中在 `src/main/resources/application.yaml`：

- `app.integration.channels`：`PARTNER_A` / `PARTNER_B` / `ERP` 三渠道，各含 `base-url`（localhost:8101/8102/8103）、`auth-token`、`signature-secret`、`read-timeout-ms`（3000）
- `app.integration.max-attempts: 5`、`retry-worker-fixed-delay-ms: 3000`、`signature-tolerance-seconds: 300`
- 绑定类：`config/ChannelProperties.java`

`src/main/resources/doc_old/schema.sql` 共 9 张表（H2 语法，设计映射 MySQL 8 生产）：

`integration_channel`（渠道）、`integration_request`（出站 outbox，状态机载体）、`integration_call_log`（AOP 调用日志）、`dead_letter`（死信）、`callback_subscription`（Flow B 订阅）、`callback_delivery`（送达/补偿记录）、`sync_watermark`（高水位游标）、`sync_job_log`（同步审计）、`idempotency_key`（`(biz_type, channel_code, biz_id)` 防重）。

> 注意：现行「API 中心」表结构见 `doc/表结构设计.html`（15 张新表，命名独立），与上述旧 demo 9 表**不是同一套**，勿混用。

## 约定与注意事项（Gotchas）

- **中文注释**：全库代码注释、README、设计文档均为简体中文，新代码保持中文注释。
- **Demo 性质**：旧版 Java 代码多处为示例级实现（占位 requestId/deadLetterId、日志替代真实补偿、`CompensationWorker` 有 `TODO 生产实现`）。生产需 MySQL 8、XXL-JOB、Redis 分布式锁等，代码注释中已标注。
- **两代设计并存**：改 doc/ 下文档时不参照旧版（doc_old、schema.sql、旧 Java 类名），避免冲突；反之改旧版代码时不混入新设计概念。
- **MapStruct + Lombok**：通过 `maven-compiler-plugin` 的 `annotationProcessorPaths` 显式配置（compile 与 test-compile 两个 execution）。新增映射字段时确保注解处理器生效（`OrderMapperImpl` 为编译期生成）。
- `pom.xml` 中 `wiremock.version` 属性已声明但**未作为依赖使用**（后续测试可用）。
- 三渠道为 localhost 演示地址，应用自身不启动这些对端；第三方对端靠静态 Demo 页 / WireMock 模拟。
- 测试仅 1 个 `@SpringBootTest contextLoads()` 冒烟测试（`src/test/java/com/deepx/apicenter/ApicenterApplicationTests.java`），启动需 8080 端口空闲。

## 文档导航

- **现行设计**（`src/main/resources/doc/`）：`API中心设计方案.md`（总纲）→ `技术架构和实现方案.md` / `可行性报告.md` / `表结构设计.html` + `schema.sql`（实现四件套）→ `API中心时序图与流程图.md` / `API中心原型.html`（流程与交互）→ `开发计划.md`（M0–M5 里程碑 + fastmoss 黄金用例，执行入口）
- `README.md` — 项目索引
- **旧版设计**（`src/main/resources/doc_old/`）：`设计文档.md`（12 章，现有代码依据）、`实现指南.md`、`schema.sql`、`api演示 demo.html`、其余可行性/原型说明文档
- `src/main/resources/static/` — Vue3 前端（`/`）
