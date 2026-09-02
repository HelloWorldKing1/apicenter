# apicenter · 项目索引

API 三方接口统一调用平台组件 —— 只做 **连接 + 适配 + 可靠传输**，不承接业务决策。

## 定位

基础 API 中转 + 字段映射的统一调用平台：应用（供应商）/ 分组 / 接口 / 监控 / 适配器 5 个功能模块；支持 JSON / XML 双协议、多鉴权（含云厂商签名与回调验签）、接口级字段映射、可靠传输（重试 / 补偿 / 死信 / 对账 / 熔断）、全链路可观测。

## 两条核心链路

1. **Flow A 出站**（调用方 → 平台 → 供应商）：适配器链（入站鉴权 → 协议解码 → 报文适配 → 字段映射 → 协议编码 → 出站鉴权=供应商签名）→ 调供应商 → 反向适配回调用方。失败按状态机处理：5xx/429 指数退避短重试 → 补偿；4xx → 死信；超时 → UNKNOWN 对账。
2. **Flow B 入站回调**（供应商回调 → 平台 → 调用方）：回调验签（凭证独立于出站签名）→ 适配器链 → 送达回调地址 → 收到即回 ack 回执（与送达解耦）→ 送达失败由补偿 worker 重送。

## 开发状态

- 设计文档已定稿（`doc/` 6 份 + schema.sql 15 张表）；数据库（MySQL PolarDB）已按 schema 建库。
- M0 三份契约设计已起草待评审（`doc/开发文档/`）。
- 旧版「ERP 订单连接器」demo 已删除（git 历史 commit `ad55cea` 之前可查）。
- 工程当前为骨架状态，按《开发计划.md》M1–M5 推进。

## 文档导航

| 交付物 | 路径 | 说明 |
|---|---|---|
| 设计总纲 | [API中心设计方案.md](src/main/resources/doc/API中心设计方案.md) | 5 模块；接口定义模型（出站中转 / 入站回调）；三类适配器（鉴权 / 协议 / 报文）+ 接口级字段映射；状态机 / 错误码 / 容错附录 |
| 实现方案 | [技术架构和实现方案.md](src/main/resources/doc/技术架构和实现方案.md) | 分层架构、技术选型、适配器链引擎、出 / 入站执行引擎、M1–M5 路线图、ADR |
| 可行性报告 | [可行性报告.md](src/main/resources/doc/可行性报告.md) | 技术可行性评估、工作量估算（约 81 人日）、风险与应对 |
| 表结构设计 | [表结构设计.html](src/main/resources/doc/表结构设计.html) | 15 张表（配置 10 + 运行 5）+ 枚举汇总 + 原型数据模型映射对照 |
| 时序与流程 | [API中心时序图与流程图.md](src/main/resources/doc/API中心时序图与流程图.md) | 配置流程、Flow A / B 时序、请求处理 + 容错流程图 |
| 交互原型 | [API中心原型.html](src/main/resources/doc/API中心原型.html) | 可交互管理面原型（数据模型与交互即事实来源） |
| 建表脚本 | [schema.sql](src/main/resources/doc/schema.sql) | MySQL 8.0，15 张表（与《表结构设计.html》一一对应） |
| 开发计划 | [开发计划.md](src/main/resources/doc/开发计划.md) | M0–M5 里程碑 + 第一个可演示版本（fastmoss 黄金用例，断言 G1–G4） |
| M0 契约（待评审） | [doc/开发文档/](src/main/resources/doc/开发文档/) | 链引擎契约 / 动态映射语义规范 / 通用客户端与对账协议 |

## 快速开始

```bash
mvn spring-boot:run   # 启动后端 :8080（连 MySQL PolarDB；本机需自行安装 Maven 与 JDK 21）
```

- `/actuator/health` 健康检查；管理面前端随 M1 落地。
- 数据库建库脚本：[doc/schema.sql](src/main/resources/doc/schema.sql)（已执行）。

## 事实来源

- **现行设计**：`src/main/resources/doc/` 六份文档（设计方案为总纲，表结构 / 实现方案 / 排期配套）+ `doc/开发文档/` M0 契约
- **工程**：`src/main/resources/application.yaml`（基础设施参数，业务配置落库）；`pom.xml`（Spring Boot 4.1 / Java 21 / MapStruct 1.6.3 / WireMock 3.9.1 预留 / `jackson-dataformat-xml`）
- **旧版 demo**（已删除，git 历史 `ed95446` 及之前）：ERP 订单连接器实现参考（@HttpExchange / @Retryable / AOP / OTel 已验证经验）
