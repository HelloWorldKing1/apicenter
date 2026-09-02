# CLAUDE.md

## 项目概述

**apicenter** —— API 三方接口统一调用平台组件。定位：**只做连接 + 适配 + 可靠传输，不承接业务决策。**

> 旧版「ERP 订单连接器」demo 已于 2026-09-02 删除（commit `ad55cea`），完整保留在 git 历史中（`git show ed95446:<path>` 可查旧实现，如 SignatureService 验签、LoggingAspect 脱敏）。当前工程按现行「API 中心」设计重建。

两条核心链路：

- **Flow A 出站**（调用方 → 组件 → 供应商）：入站鉴权 → 适配器链（协议解码 → 报文适配 → 字段映射 → 协议编码）→ 出站鉴权（供应商签名）→ 调供应商 → 反向适配回调用方。失败按状态机处理：5xx/429 指数退避重试 → 补偿；4xx → 死信；超时 → UNKNOWN 对账。
- **Flow B 入站回调**（供应商回调 → 组件 → 调用方）：回调验签（凭证独立于出站签名）→ 适配器链 → 送达回调地址 → 收到即回 ack 回执（与送达解耦）→ 送达失败由补偿 worker 重送。

## 当前开发状态（2026-09-02）

| 项 | 状态 |
|---|---|
| 设计文档 | 已定稿：`src/main/resources/doc/` 6 份 + schema.sql（15 张表） |
| M0 契约设计 | 已起草待评审：`doc/开发文档/` M0-01/02/03（每份末尾有「待评审确认点」共 9 项） |
| 旧 demo 代码 | 已删除（commit `ad55cea`），git 历史可查 |
| 数据库 | MySQL PolarDB 已按新 schema 建库（连接信息见 application.yaml） |
| 工程代码 | 仅骨架（入口类 + 冒烟测试），M1 起落地 |
| 未拍板决策 | ① M0 评审 9 点（含 Aviator 选型 D10、协议参数首期范围 D5）；② 凭证轮换存储方案（设计 §1.2 要「新旧短暂并存」但 app 表凭证仅单列，M1 凭证管理前置） |

## 设计文档（现行）

`src/main/resources/doc/`（6 份，互相引用闭环，改动需同步）：

| 文档 | 内容 |
|---|---|
| `API中心设计方案.md` | 设计总纲：应用（供应商）/ 分组 / 接口 / 监控 / 适配器 5 模块；接口定义模型（出站中转 / 入站回调）；三类适配器（鉴权 / 协议 / 报文）+ 接口级字段映射；状态机 / 错误码 / 容错附录 |
| `技术架构和实现方案.md` | 实现路径：分层架构、技术选型、适配器链引擎、出 / 入站执行引擎、M1–M5 路线图、ADR |
| `可行性报告.md` | 技术可行性评估、工作量估算（约 81 人日）、风险与应对 |
| `表结构设计.html` | 15 张表（配置 10 + 运行 5）+ 枚举汇总 + 原型数据模型映射对照 |
| `API中心时序图与流程图.md` | 配置流程、Flow A / B 时序、请求处理 + 容错流程图 |
| `API中心原型.html` | 可交互管理面原型（数据模型与交互即事实来源） |

`doc/开发文档/`（M0 契约，草稿待评审）：

| 文档 | 内容 |
|---|---|
| `M0-01链引擎契约设计.md` | UnifiedModel / Adapter / AdapterContext、六阶段链编排、绑定继承 / 覆盖解析、协议自动推导、平台默认兜底（Noop 直通）、链缓存与状态机边界 |
| `M0-02动态映射语义规范.md` | 6 操作 × param 语法、类型注册表转换矩阵、condition 沙箱（Aviator 选型）、null_strategy 四值、24 例预期输出矩阵 |
| `M0-03通用客户端与对账协议.md` | 通用 ExchangeClient（动态 URI / 凭证组装）、异常→状态机映射表、UNKNOWN 对账三分支（M2 人工 / M4 降级 / v1.1 自动查询） |

关键设计要点（改动前先读设计方案对应章节）：

- **应用 = 供应商（上游）**：出站凭证（供应商签名）+ 回调验签凭证两类分离；调用方鉴权 / 向回调地址签名由平台统一，不在模型内（§1.2 / §3.1 / §5.3）。
- **接口两种类型**：出站中转（上游路径 + 供应商签名 + 出站响应字段）/ 入站回调（回调地址 + 回调验签 + 出站侧送达报文必填 + ack 回执字段）；类型互斥字段按类型清空（§3.1）。
- **适配器三类**：鉴权 / 协议 / 报文；字段映射为接口级配置（不是适配器）；协议适配器按接口协议自动推导、不参与绑定；绑定角色 = 报文 / 供应商签名 / 回调验签，应用级默认 + 接口级覆盖（§5.1 / §5.7）。
- **字段映射**：运行时规则（source/op/target/param/nullStrategy，6 种操作），非编译期映射（§5.6）。
- **无平台侧幂等开关**：去重依赖上游对业务键幂等（§6.3）。
- **入站 ack = 回执**：收到即回、与送达解耦，无「调用方 ack → 供应商 ack」反向映射（§5.5 / §6.1）。

## 技术栈

Java 21 · Spring Boot 4.1（parent `spring-boot-starter-parent:4.1.0`）· Spring Framework 7 · Jackson 3（`tools.jackson.dataformat:jackson-dataformat-xml`）· MapStruct 1.6.3（仅固定结构映射）· JdbcTemplate + MySQL（PolarDB，连接信息见 application.yaml）· OpenTelemetry · Micrometer/Prometheus · Lombok。

持久化无 JPA/Repository，全部为 `JdbcTemplate` 直连 SQL，DDL 见 `src/main/resources/doc/schema.sql`。

> 待定：动态映射 condition 表达式内核 **Aviator**（M0-02 D10，评审通过后 M2 引入依赖）。

## 常用命令

> **注意**：仓库无 Maven wrapper（无 `mvnw` / `.mvn/`），且当前机器 `mvn` 不在 PATH，需自行安装 Maven 与 JDK 21（机器现有 JDK 25，`--release 21` 可编译，但建议装 21 对齐）。

```bash
mvn spring-boot:run   # 启动后端 :8080（连接 application.yaml 配置的 MySQL/PolarDB；库已按 doc/schema.sql 建好）
mvn test              # 跑测试（当前仅 1 个 contextLoads 冒烟测试）
mvn package           # 打可执行 jar
```

运行后可访问：`/actuator/health` 等（管理面前端 M1 落地）。

## 架构与源码结构

根包 `com.deepx.apicenter`（`src/main/java/com/deepx/apicenter/`），按技术架构分层规划（M1 起逐步落地）：

| 包 | 职责 | 落地里程碑 |
|---|---|---|
| `controller/` | 管理面 REST（应用 / 分组 / 接口 / 监控 / 适配器 5 模块）+ 接入层路由 | M1 / M2 |
| `service/` | 业务编排：配置校验、状态机流转 | M1 |
| `repository/` | JdbcTemplate 数据访问（15 张表） | M1 |
| `engine/` | 适配器链引擎 + 出站 / 入站执行引擎（M0-01 契约） | M2 / M3 |
| `adapter/` | 鉴权 / 协议 / 报文三类适配器实现 | M2 |
| `mapping/` | 动态字段映射引擎（M0-02 规范，6 操作运行时解释器） | M2 |
| `client/` | 通用声明式 HTTP 客户端（M0-03 契约，动态 URI / 凭证组装） | M2 |
| `worker/` | 补偿 / 对账 worker（按 (status, next_retry_at) 扫描） | M2 / M4 |
| `aspect/` | AOP 调用日志、traceId、脱敏 | M4 |
| `config/` | 配置与 Bean 装配 | M1 |

入口：`ApicenterApplication.java`（`@SpringBootApplication` + `@EnableScheduling` + `@EnableResilientMethods`，后者启用 Spring 7 `@Retryable`）。

## 核心状态机与容错（设计 §6）

- **出站状态机**（载体 `outbound_request.status`）：`INIT → MAPPING → SENDING → RETRYING → COMPENSATING → SUCCESS / DEAD_LETTER / UNKNOWN`。
  - 5xx/429 → 短重试（`@Retryable`，指数退避，上限 `interface.max_retries`）；重试耗尽 → 补偿
  - 4xx（非 429）→ 写 `dead_letter` → DEAD_LETTER，不重试
  - 读超时 / 连接异常 → UNKNOWN → 对账（M2 人工 / M4 超时自动降级，见 M0-03 §3）
  - **`@Retryable` 必须放在独立 Invoker 类**——Spring AOP 自调用不触发代理
- **入站送达状态**（载体 `inbound_delivery.delivery_status`）：`RECEIVED → ACKED / PENDING → ACKED / DEAD_LETTER`；送达失败仍回 ack（供应商不重发），补偿 worker 重送（按 `callback_url_snapshot`）。
- **熔断（M4）**：三态 CLOSED/OPEN/HALF_OPEN，闸门置于 @Retryable Invoker 调用前，粒度「接口 + 供应商」。
- **链失败不污染状态机**（M0-01 D7）：解码 / 映射 / 编码 / 验签失败直接错误响应 + call_log，不落运行表。

## 配置与数据模型

- 配置集中在 `src/main/resources/application.yaml`：仅基础设施参数（datasource、`retry-worker-fixed-delay-ms: 3000`、`unknown-ttl-minutes: 10`）；业务配置（应用 / 接口 / 适配器 / 字段映射）全部落库。
- `src/main/resources/doc/schema.sql`：15 张表（配置 10 + 运行 5），无数据库外键（引用完整性应用层保证，引用列建索引），与《表结构设计.html》逐表一致。

## 约定与注意事项（Gotchas）

- **中文注释**：全库代码注释、README、设计文档均为简体中文，新代码保持中文注释。
- **MapStruct + Lombok**：通过 `maven-compiler-plugin` 的 `annotationProcessorPaths` 显式配置（compile 与 test-compile 两个 execution）。MapStruct 只用于固定结构映射（统一信封组装、实体 ↔ DTO），动态映射走规则解释器（M0-02）。
- **Boot 4 不自动装配 `RestClient.Builder`**：声明式客户端用 `HttpServiceProxyFactory` + `RestClient` 手动构建（旧 demo 经验，git 历史可查）。
- **Jackson 3 包名**：`tools.jackson.*`，非 `com.fasterxml.jackson.*`。
- `pom.xml` 中 `wiremock.version` 已声明但未作为依赖使用（M2 集成测试 mock 对端可用）。
- 旧 demo 实现仅供参考（git 历史 `ed95446` 及之前），不照搬渠道特化逻辑（PARTNER_A/B、订单字段、高水位同步均不适用于新设计）。

## 文档导航

- **现行设计**（`src/main/resources/doc/`）：`API中心设计方案.md`（总纲）→ `技术架构和实现方案.md` / `可行性报告.md` / `表结构设计.html` + `schema.sql`（实现四件套）→ `API中心时序图与流程图.md` / `API中心原型.html`（流程与交互）→ `开发计划.md`（M0–M5 里程碑 + fastmoss 黄金用例，执行入口）
- **M0 契约**（`src/main/resources/doc/开发文档/`）：链引擎 / 映射语义 / 客户端对账三份（草稿待评审，M2 编码依据）
- `README.md` — 项目索引
