# CLAUDE.md

## 项目概述

**apicenter** —— API 三方接口统一调用平台组件。独立 Spring Boot 服务，作为 ERP 与第三方系统（PARTNER_A / PARTNER_B）之间的「连接器」：对外向 ERP 暴露统一、稳定的调用面，对内做字段适配、失败重试、可靠传输、全链路可观测。**只做连接 + 适配 + 可靠传输，不承接 ERP 业务决策。**

两条核心链路：

- **Flow A 出站**（ERP → 组件 → 第三方）：ERP 调 `POST /api/orders` 推送订单 → 验签 → MapStruct 字段映射（PARTNER_A 出 JSON / PARTNER_B 出 XML）→ `@HttpExchange` 调用第三方 → 反向映射返回 ERP。失败按状态机处理：500/429 指数退避重试 → 持久化补偿 → 成功；400 → 死信；超时 → UNKNOWN 对账。
- **Flow B 入站回调**（第三方 → 组件 → ERP）：第三方调 `POST /callback/{channel}/order-status` → 验签 → 映射为 ERP 事件 → 送达 ERP 回调 URL → ERP ack → 组件回第三方 ack。送达失败由补偿 worker 重发。

四大能力：字段映射（3.1）、失败重试（3.2）、请求日志（3.3）、定时/实时同步（3.4）。详见 `README.md` 与 `src/main/resources/doc/设计文档.md`（12 章）。

## 技术栈

Java 21 · Spring Boot 4.1（parent `spring-boot-starter-parent:4.1.0`）· Spring Framework 7 · Jackson 3（`tools.jackson.dataformat:jackson-dataformat-xml`）· MapStruct 1.6.3 · JdbcTemplate + H2（Demo，生产换 MySQL 8）· OpenTelemetry · Micrometer/Prometheus · Lombok。

持久化无 JPA/Repository，全部为 `JdbcTemplate` 直连 SQL，DDL 见 `src/main/resources/schema.sql`。

## 常用命令

> **注意**：仓库无 Maven wrapper（无 `mvnw` / `.mvn/`），且当前机器 `mvn` 不在 PATH，需自行安装 Maven 与 JDK 21。

```bash
mvn spring-boot:run   # 启动后端 :8080（H2 内存库，schema.sql 启动时自动执行）
mvn test              # 跑测试（现有仅 1 个 contextLoads 冒烟测试）
mvn package           # 打可执行 jar
```

运行后可访问：`/` → Vue3 前端；`/h2-console` → H2 控制台；`/actuator/health` 等。

## 架构与源码结构

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

## API 面

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders` | Flow A 推送订单。Header：`X-App-Id` / `X-Timestamp` / `X-Signature`。验签：`HMAC-SHA256(appId + timestamp + orderId, erp-secret)`，时间戳容差 300s，失败返回 401 |
| POST | `/api/orders/query` | UNKNOWN 对账查询（示例级占位实现） |
| POST | `/callback/{channel}/order-status` | Flow B 第三方回调。Header：`X-Partner-Signature` / `X-Timestamp`，用该渠道密钥验签，失败返回 401 `{"code":401,"msg":"signature invalid"}` |

## 核心状态机与关键设计

- **出站状态机**（`OrderSyncService.RequestStatus`，载体 `integration_request.status`）：`INIT → MAPPING → SENDING → RETRYING → COMPENSATING → SUCCESS / DEAD_LETTER / UNKNOWN`。
  - 5xx/429 → `PartnerInvoker` 的 `@Retryable` 短重试（maxRetries=4，指数退避 200ms×2，上限 2s）；重试耗尽仍失败 → 补偿
  - 4xx（非 429）→ 写 `dead_letter` → DEAD_LETTER，不重试
  - 读超时 / 连接异常 → UNKNOWN → 对账（当前示例直接置 SUCCESS）
- **`@Retryable` 必须放在独立类 `PartnerInvoker`** 中，而不是 `OrderSyncService` 内部——Spring AOP 自调用不触发代理（见 `PartnerInvoker` 类注释）。
- **入站送达状态**（`callback_delivery.delivery_status`）：RECEIVED → ERP_ACKED / PENDING（失败时仍回 ack，第三方不重发，由 `CompensationWorker` 重送）。
- **超时映射**：client 读超时（默认 3000ms，`read-timeout-ms`）触发 `ResourceAccessException` → UNKNOWN 分支。
- 所有出站 client 由 `config/RestClientConfig` 用 `HttpServiceProxyFactory` + `RestClient` 构建（Boot 4 不自动装配 `RestClient.Builder`）。

## 配置与数据模型

配置集中在 `src/main/resources/application.yaml`：

- `app.integration.channels`：`PARTNER_A` / `PARTNER_B` / `ERP` 三渠道，各含 `base-url`（localhost:8101/8102/8103）、`auth-token`、`signature-secret`、`read-timeout-ms`（3000）
- `app.integration.max-attempts: 5`、`retry-worker-fixed-delay-ms: 3000`、`signature-tolerance-seconds: 300`
- 绑定类：`config/ChannelProperties.java`

`src/main/resources/schema.sql` 共 9 张表（H2 语法，设计映射 MySQL 8 生产）：

`integration_channel`（渠道）、`integration_request`（出站 outbox，状态机载体）、`integration_call_log`（AOP 调用日志）、`dead_letter`（死信）、`callback_subscription`（Flow B 订阅）、`callback_delivery`（送达/补偿记录）、`sync_watermark`（高水位游标）、`sync_job_log`（同步审计）、`idempotency_key`（`(biz_type, channel_code, biz_id)` 防重）。

## 约定与注意事项（Gotchas）

- **中文注释**：全库代码注释、README、设计文档均为简体中文，新代码保持中文注释。
- **Demo 性质**：多处为示例级实现（占位 requestId/deadLetterId、日志替代真实补偿、`CompensationWorker` 有 `TODO 生产实现`）。生产需 MySQL 8、XXL-JOB、Redis 分布式锁等，代码注释中已标注。
- **MapStruct + Lombok**：通过 `maven-compiler-plugin` 的 `annotationProcessorPaths` 显式配置（compile 与 test-compile 两个 execution）。新增映射字段时确保注解处理器生效（`OrderMapperImpl` 为编译期生成）。
- `pom.xml` 中 `wiremock.version` 属性已声明但**未作为依赖使用**（后续测试可用）。
- 三渠道为 localhost 演示地址，应用自身不启动这些对端；第三方对端靠静态 Demo 页 / WireMock 模拟。
- 测试仅 1 个 `@SpringBootTest contextLoads()` 冒烟测试（`src/test/java/com/deepx/apicenter/ApicenterApplicationTests.java`），启动需 8080 端口空闲。

## 文档导航

- `README.md` — 设计文档与 Demo 索引（功能、接口清单、技术栈、Demo 动线）
- `src/main/resources/doc/设计文档.md` — 12 章设计文档（定位/架构/链路时序/字段映射/重试补偿状态机/日志链路/定时同步/数据模型/API/验收映射）
- `src/main/resources/doc/实现指南.md` — 各能力「如何实现」及代码位置
- `src/main/resources/api演示 demo.html` — 独立静态交互演示页
- `src/main/resources/static/` — Vue3 前端（`/`）
