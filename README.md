# apicenter · 项目索引

API 三方接口统一调用平台组件 —— 只做 **连接 + 适配 + 可靠传输**，不承接业务决策。

## 定位

基础 API 中转 + 字段映射的统一调用平台：应用（供应商）/ 分组 / 接口 / 监控 / 适配器 5 个功能模块；支持 JSON / XML 双协议、多鉴权（含云厂商签名与回调验签）、接口级字段映射、可靠传输（重试 / 补偿 / 死信 / 对账 / 熔断）、全链路可观测。

## 两条核心链路

1. **Flow A 出站**（调用方 → 平台 → 供应商）：适配器链（入站鉴权 → 协议解码 → 报文适配 → 字段映射 → 协议编码 → 出站鉴权=供应商签名）→ 调供应商 → 反向适配回调用方。失败按状态机处理：5xx/429 指数退避短重试 → 补偿；4xx → 死信；超时 → UNKNOWN 对账。**✅ M2 已交付**。
2. **Flow B 入站回调**（供应商回调 → 平台 → 调用方）：回调验签（凭证独立于出站签名）→ 适配器链 → 送达回调地址 → 收到即回 ack 回执（与送达解耦）→ 送达失败由补偿 worker 重送。**⏳ M3 交付**（配置模型已在 M1 落地，运行时链路待建）。

## 开发状态（2026-09-03）

- **M0 完成**：三份契约（链引擎 / 映射语义 / 客户端对账）+ 凭证轮换方案 M0-04 全部评审通过；schema 16 张表。
- **M1 完成**：管理面后端（应用 / 分组 / 接口 / 适配器 / 凭证）+ Vue3 前端（`frontend/`，6 页面）。
- **M2 完成**：链引擎 + 映射引擎（Aviator 5）+ 通用客户端（RestClient 直调）+ 出站状态机 + 补偿 worker；fastmoss 黄金用例 G1-G4 在 WireMock 对端端到端跑通 = **首个可演示版本**。
- **测试**：全库 45 个 @Test 全绿（M1 相关 22：M1IntegrationTest 14 + CryptoServiceTest 7 + 冒烟 1；M2 相关 23：MappingEngineTest 14 + M2IntegrationTest 6 + JsonProtocolAdapterTest 3）。
- **待办**：真实 fastmoss 联调（待 token）；M3（XML 编解码 + 入站回调链路）；多鉴权并行线（HMAC / 云厂商签名 / 回调验签）。

## 文档导航

| 交付物 | 路径 | 说明 |
|---|---|---|
| 设计总纲 | [API中心设计方案.md](src/main/resources/doc/API中心设计方案.md) | 5 模块；接口定义模型（出站中转 / 入站回调）；三类适配器（鉴权 / 协议 / 报文）+ 接口级字段映射；状态机 / 错误码 / 容错附录 |
| 实现方案 | [技术架构和实现方案.md](src/main/resources/doc/技术架构和实现方案.md) | 分层架构、技术选型、适配器链引擎、出 / 入站执行引擎、M1–M5 路线图、ADR |
| 可行性报告 | [可行性报告.md](src/main/resources/doc/可行性报告.md) | 技术可行性评估、工作量估算（约 81 人日）、风险与应对 |
| 表结构设计 | [表结构设计.html](src/main/resources/doc/表结构设计.html) | 16 张表（配置 11 + 运行 5）+ 枚举汇总 + 原型数据模型映射对照 |
| 时序与流程 | [API中心时序图与流程图.md](src/main/resources/doc/API中心时序图与流程图.md) | 配置流程、Flow A / B 时序、请求处理 + 容错流程图 |
| 交互原型 | [API中心原型.html](src/main/resources/doc/API中心原型.html) | 可交互管理面原型（数据模型与交互即事实来源） |
| 建表脚本 | [schema.sql](src/main/resources/doc/schema.sql) | MySQL 5.7/8.0 双兼容，16 张表（与《表结构设计.html》一一对应） |
| 开发计划 | [开发计划.md](src/main/resources/doc/开发计划.md) | M0–M5 里程碑 + 第一个可演示版本（fastmoss 黄金用例，断言 G1–G4） |
| M0 契约（已评审通过） | [doc/开发文档/](src/main/resources/doc/开发文档/) | 链引擎契约 / 动态映射语义规范 / 通用客户端与对账协议 / 凭证轮换存储方案 |
| M2 验收方案 | [M2手动验收测试方案.md](src/main/resources/doc/开发文档/M2手动验收测试方案.md) | httpbin/postman-echo 模拟上游的五个分支手动验收步骤 |
| M2 代码评审 | [M2代码评审记录.md](src/main/resources/doc/开发文档/M2代码评审记录.md) | 四路评审问题清单与修复进度 |
| 踩坑记录 | [技术踩坑记录.md](src/main/resources/doc/开发文档/技术踩坑记录.md) | Spring 7 / Jackson 3 / WireMock 3 API 差异与经验（写代码前先查） |

## 快速开始

```bash
mvn spring-boot:run   # 启动后端 :8080（连 MySQL PolarDB；本机需自行安装 Maven 与 JDK 21）

cd frontend
npm install           # 首次安装前端依赖（Node 22）
npm run dev           # 前端 dev server :5173（/api 代理到 8080）
npm run build         # 构建产物输出到 src/main/resources/static/（后端直接 serve）
```

- 管理面 `http://localhost:5173`（dev）/ `http://localhost:8080`（build 产物）；`/actuator/health` 健康检查。
- 启动时 SeedDataInitializer 幂等导入 fastmoss 黄金用例种子（`app.api-center.seed.enabled` 可关）。
- 执行面（Flow A）平台侧路径直接打后端：`POST http://localhost:8080/fastmoss/creatorList`。

## 事实来源

- **现行设计**：`src/main/resources/doc/` 六份文档（设计方案为总纲，表结构 / 实现方案 / 排期配套）+ `doc/开发文档/` M0 契约与验收 / 评审 / 踩坑记录
- **工程**：`src/main/resources/application.yaml`（基础设施参数，业务配置落库）；`pom.xml`（Spring Boot 4.1 / Java 21 / MapStruct 1.6.3 / Aviator 5.4.3 / WireMock 3.9.1 预留 / `jackson-dataformat-xml`）
- **旧版 demo**（已删除，git 历史 `ed95446` 及之前）：ERP 订单连接器实现参考（@HttpExchange / @Retryable / AOP / OTel 已验证经验）
