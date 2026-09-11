# CLAUDE.md

## 项目概述

**apicenter** —— API 三方接口统一调用平台组件。定位：**只做连接 + 适配 + 可靠传输，不承接业务决策。**

> 旧版「ERP 订单连接器」demo 已于 2026-09-02 删除（commit `ad55cea`），完整保留在 git 历史中（`git show ed95446:<path>` 可查旧实现，如 SignatureService 验签、LoggingAspect 脱敏）。当前工程按现行「API 中心」设计重建。

两条核心链路：

- **Flow A 出站**（调用方 → 组件 → 供应商）：入站鉴权 → 适配器链（协议解码 → 报文适配 → 字段映射 → 协议编码）→ 出站鉴权（供应商签名）→ 调供应商 → 反向适配回调用方。失败按状态机处理：5xx/429 指数退避重试 → 补偿；4xx → 死信；超时 → UNKNOWN 对账。
- **Flow B 入站回调**（供应商回调 → 组件 → 调用方）：回调验签（凭证独立于出站签名）→ 适配器链 → 送达回调地址 → 收到即回 ack 回执（与送达解耦）→ 送达失败由补偿 worker 重送。

## 当前开发状态（2026-09-07）

| 项 | 状态 |
|---|---|
| 设计文档 | 已定稿：`src/main/resources/doc/` 6 份 + schema.sql（**19 张表**，M4 新增 reconcile_audit / alert_event，M5 后新增 outbound_request_state_log 状态链） |
| M0 契约设计 | **已评审通过 v1.0（2026-09-02）**：`doc/开发文档/` M0-01/02/03/04（确认点全部通过） |
| 旧 demo 代码 | 已删除（commit `ad55cea`），git 历史可查 |
| 数据库 | MySQL PolarDB 已按新 schema 建库（连接信息见 application.yaml）；M4 DDL（两表 + idx_outreq_updated 索引）已于 2026-09-04 应用到开发库 |
| 工程代码 | **M1 + M2 + M3 + M4 已落地并测试通过；M5.1/M5.2 已落地（全库 174 个 @Test 基准）；M5 后状态链已落地（全库归属 180 = M1 22 / M2 23 / M3 62 / M4 57 / M5 10 + StateChainIntegrationTest 6；2026-09-08）**。M4 = 熔断器三态 + UNKNOWN 人工对账 + TTL 降级 + 死信重放 + GatewayGuard 防护 + call_log 脱敏与 traceId 贯穿 + 指标告警。M5.1 = 接口版本快照与回滚（config_json 序列化 / 回滚复用全量替换 + 乐观锁；版本 v1.0 起每次配置变更 / 回滚 +0.1 步进、历史只增不回退 / 版本查询端点）；M5.2 = 适配器绑定即实例 + 解析时机上移（绑定/映射/参数烘焙进缓存链，凭证保持实时）+ ConfigChangedEvent 事件失效 + test 端点 chainTrace + D6'（2026-09-08 定稿：adapter.name 全表唯一；同 (impl, version) 允许多启用；version 不再路由）+ 前端版本历史/变更说明。测试归属：M1 22 / M2 23 / M3 62 / M4 57 / **M5 10**（M5IntegrationTest 7 + SnapshotSerializerTest 3）+ 状态链 6 |
| 里程碑计划 | **M4 手动验收（方案已细化，2026-09-05）待完成；M5.3 压测执行 + M5 手动验收待排期**——M5 开发计划已评审定稿（2026-09-04 一轮 + 09-07 二轮），D-M5-1~3 即编码依据，总盘 9 人日 |
| 未拍板决策 | 无（M0 全部评审通过；M4/M5 计划均已评审定稿） |

## 设计文档（现行）

`src/main/resources/doc/`（6 份，互相引用闭环，改动需同步）：

| 文档 | 内容 |
|---|---|
| `API中心设计方案.md` | 设计总纲：应用（供应商）/ 分组 / 接口 / 监控 / 适配器 5 模块；接口定义模型（出站中转 / 入站回调）；三类适配器（鉴权 / 协议 / 报文）+ 接口级字段映射；状态机 / 错误码 / 容错附录 |
| `技术架构和实现方案.md` | 实现路径：分层架构、技术选型、适配器链引擎、出 / 入站执行引擎、M1–M5 路线图、ADR |
| `可行性报告.md` | 技术可行性评估、工作量估算（约 81 人日）、风险与应对 |
| `表结构设计.html` | 19 张表（配置 11 + 运行 8，M4 增 reconcile_audit / alert_event，M5 后增 outbound_request_state_log）+ 枚举汇总 + 原型数据模型映射对照 |
| `API中心时序图与流程图.md` | 配置流程、Flow A / B 时序、请求处理 + 容错流程图 |
| `API中心原型.html` | 可交互管理面原型（数据模型与交互即事实来源） |
| `API中心项目说明.md` | **面向使用者的项目总览**（非设计文档）：定位 / 核心概念 / 架构 / 两条链路 / 数据模型 / 状态机容错 / 错误码；对外介绍、新人入门的首选入口 |
| `API中心使用教程.md` | **面向使用者的上手指南**（非设计文档）：环境准备 → 启动 → 接口配置 → 调用验证 → 监控运维 → FAQ（界面操作 + 等价 curl） |

`doc/开发文档/`（M0 契约与凭证方案均已评审通过）：

| 文档 | 内容                                                                                                                                                                                                                                                                  |
|---|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `M0-01链引擎契约设计.md` | UnifiedModel / Adapter / AdapterContext、六阶段链编排、绑定继承 / 覆盖解析、协议自动推导、平台默认兜底（Noop 直通）、链缓存与状态机边界                                                                                                                               |
| `M0-02动态映射语义规范.md` | 6 操作 × param 语法、类型注册表转换矩阵、condition 沙箱（Aviator 选型）、null_strategy 四值、24 例预期输出矩阵                                                                                                                                                        |
| `M0-03通用客户端与对账协议.md` | 通用 ExchangeClient（动态 URI / 凭证组装）、异常→状态机映射表、UNKNOWN 对账三分支（M2 人工 / M4 降级 / v1.1 自动查询）                                                                                                                                                |
| `M0-04凭证轮换存储方案.md` | 已评审通过：app_credential 凭证表（第 16 张）+ ACTIVE/ROTATING/RETIRED 状态机 + 验签并存 / 签名激活 + AES-256-GCM 加密约定                                                                                                                                            |
| `M3开发计划.md` | **M3 编码依据（2026-09-03 定稿，七轮评审）**：D-M3-1 XML 语义 / D-M3-2 入站引擎与 HMAC 回调验签 / D-M3-3 RESP·ACK 语义 / D-M3-4 响应收敛；任务拆解 12 人日、自动化测试点 B1-B6·X1-X3、手动验收方案（本地 WireMock stub 随仓库 `src/test/resources/m3-manual-stubs/`） |
| `M4开发计划.md` | **M4 编码依据（2026-09-04 评审定稿）**：D-M4-1 熔断 / D-M4-2 UNKNOWN 对账（人工 + TTL）/ D-M4-3 死信重放 / D-M4-4 call_log 双向与脱敏 / D-M4-5 指标与告警 / D-M4-6 接入层防护；总盘 12 人日（含 M2 缺口承接 2 人日）                                                  |
| `M5开发计划.md` | **M5 编码依据（2026-09-04 一轮 + 2026-09-07 二轮修订；M5.1/M5.2 已实施，M5.3 待压测）**：D-M5-1 接口版本快照与回滚 / D-M5-2 适配器灰度绑定 + 解析时机上移 + 链缓存事件失效 + 停用即回退 / D-M5-3 压测调优与生产加固；总盘 9 人日；前置 = M4 出口                      |
| `M4手动验收测试方案.md` | M4 手动端到端验收（约 35 分钟，按实施后实际行为校准）：三阶段 = 可观测 / 熔断与对账 / 死信·告警·限流，stub 随仓库 `src/test/resources/m4-manual-stubs/`；M2/M3 同名方案同目录                                                                                         |
| `M5手动验收测试方案.md` | M5 手动端到端验收（按 M5 实施后实际行为校准）：三阶段 = 版本快照与回滚 / 灰度绑定与即时生效 / 压测与总巡检，见 `src/main/resources/doc/开发文档/M5手动验收测试方案.md`                                                                                                |
| `端到端闭环演示方案.md` | **演示脚本（界面配置 → 真实供应商 / 真实 XML → 运维回归）**：串讲《整体测试方案》§1.5/§4/§5/§6/§6.5(evoLink)/§6.6(真实 XML)/§7/§8/§10 成一条叙事线，每环节给「操作 / 原理 / 设计思路 / 实现方式 / 自检」 |
| `整体测试方案.md` | **全项目测试总纲（v2.0）**：§1.5 速通（一键准备+黄金链路冒烟）+ 用例库（C 配置 / F 出站 / B 入站 / O 可观测 / V 版本·变更说明·复制 / 自动化全库）/ 回归矩阵 R0-R6 / reset-dev.sql 清理 / 工具箱；执行入口见 `src/main/resources/doc/开发文档/整体测试方案.md`         |

关键设计要点（改动前先读设计方案对应章节）：

- **应用 = 供应商**：出站凭证（供应商签名）+ 回调验签凭证两类分离；调用方鉴权 / 向回调地址签名由平台统一，不在模型内（§1.2 / §3.1 / §5.3）。
- **接口两种类型**：出站中转（供应商接口路径 + 供应商签名 + 出站响应字段）/ 入站回调（回调地址 + 回调验签 + 出站侧送达报文必填 + ack 回执字段）；类型互斥字段按类型清空（§3.1）。
- **适配器三类**：鉴权 / 协议 / 报文；字段映射为接口级配置（不是适配器）；协议适配器按接口协议自动推导、不参与绑定；绑定角色 = 报文 / 供应商签名 / 回调验签，应用级默认 + 接口级覆盖（§5.1 / §5.7）。
- **字段映射**：运行时规则（source/op/target/param/nullStrategy，6 种操作），非编译期映射（§5.6）。
- **无平台侧幂等开关**：去重依赖供应商对业务键幂等（§6.3）。
- **入站 ack = 回执**：收到即回、与送达解耦，无「调用方 ack → 供应商 ack」反向映射（§5.5 / §6.1）。

## 技术栈

Java 21 · Spring Boot 4.1（parent `spring-boot-starter-parent:4.1.0`）· Spring Framework 7 · Jackson 3（`tools.jackson.dataformat:jackson-dataformat-xml`）· MapStruct 1.6.3（仅固定结构映射）· JdbcTemplate + MySQL（PolarDB，连接信息见 application.yaml）· OpenTelemetry · Micrometer/Prometheus · Lombok。

持久化无 JPA/Repository，全部为 `JdbcTemplate` 直连 SQL，DDL 见 `src/main/resources/doc/schema.sql`。

> 动态映射 condition 表达式内核 **Aviator 5**（M0-02 D10，M2 已落地：`com.googlecode.aviator:aviator`，纯解释器无反射、天然防注入）。

## 常用命令

> **注意**：仓库无 Maven wrapper（无 `mvnw` / `.mvn/`），且当前机器 `mvn` 不在 PATH，需自行安装 Maven 与 JDK 21（机器现有 JDK 25，`--release 21` 可编译，但建议装 21 对齐）。

```bash
mvn spring-boot:run   # 启动后端 :8080（连接 application.yaml 配置的 MySQL/PolarDB；库已按 doc/schema.sql 建好）
mvn test              # 跑测试（CryptoServiceTest 纯单测 + M1IntegrationTest 集成，后者连开发 PolarDB）
mvn package           # 打可执行 jar

cd frontend
npm install           # 首次安装前端依赖（Node 22）
npm run dev           # 前端 dev server :5173（/api 代理到 8080）
npm run build         # 构建产物输出到 src/main/resources/static/（后端直接 serve）
```

运行后可访问：管理面 `http://localhost:5173`（dev）/ `http://localhost:8080`（build 产物）；`/actuator/health` 健康检查。fastmoss 种子默认不自动导入（`app.api-center.seed.enabled=false`）：需要演示基线时执行 `POST /api/admin/seed/import` 手动导入。

## 架构与源码结构

根包 `com.deepx.apicenter`（`src/main/java/com/deepx/apicenter/`），按技术架构分层规划（M1 起逐步落地）：

| 包 | 职责 | 落地里程碑 |
|---|---|---|
| `controller/` | 管理面 REST（应用 / 分组 / 接口 / 监控 / 适配器 5 模块）+ 接入层路由 | M1 / M2 / M4（监控 + 死信重放 + 对账端点） |
| `service/` | 业务编排：配置校验、状态机流转、接入层防护（GatewayGuard） | M1 / M4 |
| `repository/` | JdbcTemplate 数据访问（19 张表） | M1 / M4（reconcile_audit / alert_event）/ M5 后（state_log） |
| `engine/` | 适配器链引擎 + 出站 / 入站执行引擎 + 熔断器（CircuitBreakerRegistry） | M2 / M3 / M4 |
| `adapter/` | 鉴权 / 协议 / 报文三类适配器实现 | M2 |
| `mapping/` | 动态字段映射引擎（M0-02 规范，6 操作运行时解释器） | M2 |
| `client/` | 通用声明式 HTTP 客户端（M0-03 契约，动态 URI / 凭证组装） | M2 |
| `worker/` | 补偿 / 对账 / 告警 worker（按 (status, next_retry_at) 扫描）+ call_log 异步写 | M2 / M3（入站重送）/ M4（TTL 降级 + 告警） |
| `aspect/` | AOP 调用日志、traceId、脱敏（SensitiveDataMasker） | M4（已落地） |
| `config/` | 配置与 Bean 装配 | M1 |

入口：`ApicenterApplication.java`（`@SpringBootApplication` + `@EnableScheduling` + `@EnableResilientMethods`，后者启用 Spring 7 `@Retryable`）。

前端 `frontend/`（Vue3 + Vite + Element Plus，M1 设计 §4）：`src/views/` 六页面（Dashboard / Apps / Groups / Interfaces / Adapters / Monitor）+ `components/ParamTable` 参数编辑 + `api/http.js` 统一信封解包；原型交互平移自 `doc/API中心原型.html`。管理面 REST 前缀 `/api/admin`（controller/admin 六个 Controller：应用 / 分组 / 接口 / 适配器 / 凭证 / 监控），统一信封 `{code, msg, data}`。Monitor 页 M4 已接真数据（统计卡 / 调用日志 / 对账 UNKNOWN / 死信 / 告警五区块）。

## 核心状态机与容错（设计 §6）

- **出站状态机**（载体 `outbound_request.status`）：`INIT → MAPPING → SENDING → RETRYING → COMPENSATING → SUCCESS / DEAD_LETTER / UNKNOWN`。
  - 5xx/429 → 短重试（`@Retryable`，指数退避，上限 `interface.max_retries`）；重试耗尽 → 补偿
  - 4xx（非 429）→ 写 `dead_letter` → DEAD_LETTER，不重试
  - 读超时 / 连接异常 → UNKNOWN → 对账（M2 人工 / M4 超时自动降级，见 M0-03 §3）
  - **`@Retryable` 必须放在独立 Invoker 类**——Spring AOP 自调用不触发代理
- **入站送达状态**（载体 `inbound_delivery.delivery_status`）：`RECEIVED → ACKED / PENDING → ACKED / DEAD_LETTER`；送达失败仍回 ack（供应商不重发），补偿 worker 重送（按 `callback_url_snapshot`）。
- **熔断（M4 已落地）**：三态 CLOSED/OPEN/HALF_OPEN，闸门置于 @Retryable Invoker 调用前，粒度「接口 + 供应商」；计数口径 = 每请求一次（@Retryable 内部重试不逐次计数）；OPEN 短路转 COMPENSATING 顺延（不 incrementAttempt），恢复后由补偿 worker 补做。
- **UNKNOWN 对账（M4 已落地）**：人工置位（SUCCESS / COMPENSATING，写 reconcile_audit，source=MANUAL）+ TTL 10 分钟自动降级（source=TTL）两来源审计；对账自动查询 v1.1（M0-03 C3）。
- **链失败 / 防护拒绝均不污染状态机**（M0-01 D7 / D-M4-6）：解码 / 映射 / 编码 / 验签失败直接错误响应 + call_log，不落运行表；QPS / 日配额 / IP 名单在 GatewayGuard 防护层拒绝（42901 / 42902 / 40103），同样不落运行表。

## 配置与数据模型

- 配置集中在 `src/main/resources/application.yaml`：仅基础设施参数（datasource、`retry-worker-fixed-delay-ms: 3000`、`unknown-ttl-minutes: 10`）；业务配置（应用 / 接口 / 适配器 / 字段映射）全部落库。
- `src/main/resources/doc/schema.sql`：19 张表（配置 11 + 运行 8，M4 新增 reconcile_audit / alert_event + idx_outreq_updated，M5 后新增 outbound_request_state_log + adapter.name 唯一），无数据库外键（引用完整性应用层保证，引用列建索引），与《表结构设计.html》逐表一致。

## 约定与注意事项（Gotchas）

- **中文注释**：全库代码注释、README、设计文档均为简体中文，新代码保持中文注释。
- **MapStruct + Lombok**：通过 `maven-compiler-plugin` 的 `annotationProcessorPaths` 显式配置（compile 与 test-compile 两个 execution）。MapStruct 只用于固定结构映射（统一信封组装、实体 ↔ DTO），动态映射走规则解释器（M0-02）。
- **Boot 4 不自动装配 `RestClient.Builder`**：手动构建 `RestClient` Bean（`RestClientConfig`）。
- **Spring 7 内置 @Retryable**（`org.springframework.resilience.annotation`，**不是 spring-retry**）：退避参数内联（无 @Backoff）、耗尽透传原异常（无 RetryExhaustedException）。**大坑：`maxRetriesString` 的 SpEL 只在方法首次调用时求值一次（MethodRetrySpec 按方法缓存），ThreadLocal + SpEL 动态次数方案不生效**——动态重试预算必须走 `@Retryable(predicate=...)` 扩展点 + 引擎 `beginRetryBudget/endRetryBudget` 包裹（实现见 `UpstreamInvoker`）。详见《技术踩坑记录.md》§1.1。
- **Spring 7 声明式客户端 URI 模板坑**：`@HttpExchange` 的动态完整 URL 模板变量会被路径编码（scheme 丢失）——**动态 URL 一律 RestClient 直调**（`uri(URI)`），且需 `defaultStatusHandler` 禁用默认 4xx/5xx 抛异常（引擎分类）。详见《技术踩坑记录.md》§3。
- **Jackson 3**：包名 `tools.jackson.*`；`JsonNode.fields()` 已更名为 `properties()`。
- **WireMock 3**：verify 用 `postRequestedFor(urlEqualTo(...))` + `equalTo(...)`；请求计数跨测试累积，`@BeforeEach` 需 `resetAll()`。
- **`mvn test` 不清旧产物**：删源文件后旧 class 残留在 target/classes 会被 Spring 扫描装配——结构变更务必 `mvn clean test`。
- **列表接口不带子表**：断言/校验接口子表（params/mappings 等）必须走 `detail()`，`list()` 的子表恒空。
- **熔断 / 限流 / 日配额为单实例内存口径**：多实例部署各实例独立（v1.1 分布式，日配额重启清零）；QPS 为固定秒级窗口（交界突刺最坏 2×limit）；WireMock 占 18080 与集成测试同端口，手动验收期间勿同时跑 `mvn test`。语义详见《M4开发计划.md》。
- **M5 链缓存烘焙与事件失效**：绑定解析 / 映射规则 / 入站参数声明在链装配时一次解析烘焙进缓存链（凭证仍每请求实时读）——**配置变更后绑定/协议即时生效依赖 `ConfigChangedEvent` 事件失效**：InterfaceService（update/rollback/publish/offline/delete）→ INTERFACE 精准移除；AdapterService（增改/启停/删）、AppService（默认绑定/启停）→ ADAPTER/APP 全清；**新增配置入口必须补发事件**（漏一处最长 5 分钟不生效，TTL 兜底）。凭证轮换不进清单（每请求实时读，天然即时）。
- **适配器 D6'（2026-09-08 定稿，替换 M5 灰度版本矩阵）**：adapter.name 全表唯一；同 (impl, version) 允许多条启用并存（实例靠 id + name 区分）；多实例并行首选同 impl 不同 version；binding.version **不再路由**——绑定即实例（恒用绑定行 adapter_id），version 仅记录/留痕；目标实例缺失 / 停用 → 逐层回退应用默认 → Noop。
- **接口变更说明走 `X-Change-Note` 请求头**（不扩展 InterfaceRequest DTO）：随 PUT 保存生成新版本快照的 change_note；版本历史 / 回滚端点 `GET/POST /api/admin/interfaces/{id}/versions...`、回滚 body {targetVersion, operator, reason, currentVersion}（目标缺失 40403 / 乐观锁冲突 40001）。
- **test 端点响应已包装**：`POST /{id}/test` data 变为 `{chainTrace, result}`（D-M5-2 留痕通道 3，强制实时解析）；前端解析相应调整。
- **更多 Spring 7 / Jackson 3 / WireMock 3 踩坑**：见 `doc/开发文档/技术踩坑记录.md`（写代码前先查）。
- **术语口径（2026-09-08 定稿）**：用户可见文案与文档用「**供应商**」表被代理的角色（供应商 5xx/拒绝/超时/返回、依赖供应商幂等）、「**供应商接口路径**」表出站路径；`upstreamPath` / `upstream_path` / `UpstreamInvoker` 为稳定契约与内部标识**不改名**；链路方向叙述（调供应商）与代码内部注释可保留「上游」。勿引入「第三方」作主术语（与平台客户歧义）。
- 旧 demo 实现仅供参考（git 历史 `ed95446` 及之前），不照搬渠道特化逻辑（PARTNER_A/B、订单字段、高水位同步均不适用于新设计）。

## 文档导航

- **现行设计**（`src/main/resources/doc/`）：`API中心设计方案.md`（总纲）→ `技术架构和实现方案.md` / `可行性报告.md` / `表结构设计.html` + `schema.sql`（实现四件套）→ `API中心时序图与流程图.md` / `API中心原型.html`（流程与交互）→ `开发计划.md`（M0–M5 里程碑 + fastmoss 黄金用例，执行入口）
- **M0 契约**（`src/main/resources/doc/开发文档/`）：链引擎 / 映射语义 / 客户端对账三份 + 凭证轮换存储方案 M0-04（全部已评审通过，M1/M2 编码依据）
- **里程碑计划**（`src/main/resources/doc/开发文档/`）：`M3开发计划.md` / `M4开发计划.md`（均已实施）/ `M5开发计划.md`（已评审定稿，下一步）/ 各里程碑手动验收方案 + 代码评审记录
- **踩坑记录**（`src/main/resources/doc/开发文档/技术踩坑记录.md`）：Spring 7 / Jackson 3 / WireMock 3 API 差异与经验（写代码前先查）
- `README.md` — 项目索引
