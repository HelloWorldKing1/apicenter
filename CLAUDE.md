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
| 设计文档 | 已定稿：`src/main/resources/doc/` 6 份 + schema.sql（**25 张表**，M4 新增 reconcile_audit / alert_event，M5 后新增 outbound_request_state_log 状态链，前置编排新增 interface_step，账号登录新增 admin_user / admin_session，**入站鉴权新增 client_app / client_credential / access_auth_log**） |
| M0 契约设计 | **已评审通过 v1.0（2026-09-02）**：`doc/开发文档/` M0-01/02/03/04（确认点全部通过） |
| 旧 demo 代码 | 已删除（commit `ad55cea`），git 历史可查 |
| 数据库 | MySQL PolarDB 已按新 schema 建库（连接信息见 application.yaml）；M4 DDL（两表 + idx_outreq_updated 索引）已于 2026-09-04 应用到开发库 |
| 工程代码 | **M1 + M2 + M3 + M4 已落地并测试通过；M5.1/M5.2 已落地；M5 后状态链已落地；D-PS-0 接口级读超时已落地；前置接口编排（PS-1..PS-9 + 可观测二期）已落地；管理面账号登录（认证）已落地（全库 290 @Test，针对性验证全绿，2026-09-18）**。M4 = 熔断器三态 + UNKNOWN 人工对账 + TTL 降级 + 死信重放 + GatewayGuard 防护 + call_log 脱敏与 traceId 贯穿 + 指标告警。M5.1 = 接口版本快照与回滚（config_json 序列化 / 回滚复用全量替换 + 乐观锁；版本 v1.0 起每次配置变更 / 回滚 +0.1 步进、历史只增不回退 / 版本查询端点）；M5.2 = 适配器绑定即实例 + 解析时机上移（绑定/映射/参数烘焙进缓存链，凭证保持实时）+ ConfigChangedEvent 事件失效 + test 端点 chainTrace + D6'（2026-09-08 定稿：adapter.name 全表唯一；同 (impl, version) 允许多启用；version 不再路由）+ 前端版本历史/变更说明。 |
| 应用凭证内联 + 报文美化（2026-09-11） | 已落地：① 应用弹窗内联凭证卡片（方案 A，D1–D5 已拍板，见《应用凭证配置改造方案.md》v0.2）+ E2 修复（过期 ROTATING 不再阻塞 prepare）；列表「凭证」列已于 2026-09-12 按使用反馈移除（后端 has*Credential 字段保留）；② 调用日志 / 状态机 Tab / Dashboard 抽屉 / 死信 payload 报文美化（JSON·XML·form·头串；只增删空白，19 位数字等 token 逐字节不变；语法高亮 / 折行 / 行号 / 全屏 / 折叠 / 复制含上下文 / >256KB Worker 后台格式化，见《接口监控设计方案》§8）；③ 前端零依赖测试 `cd frontend && npm test`（单测 83 例 + 组件 SSR 冒烟 18 例）+ `npm run lint`（ESLint 扁平配置） |
| 管理面账号登录（2026-09-18） | **只做认证、不做权限**（此前列为 B 类 P0「管理面无鉴权」的缺口）：`admin_user` + `admin_session`（**第 21/22 张表**，开发库已建）；不透明 Bearer 令牌（**库内只存 SHA-256 摘要**，明文只在登录响应出现一次）；口令 PBKDF2-HMAC-SHA256（JDK 自带，120k 迭代 + 随机盐 + 常量时间比较）；TTL 12h + 惰性续期（≤1 次写/会话·小时）；登出删行、改密吊销其他会话 ⇒ 即时失效；连续失败 5 次锁定 5 分钟；注册开放但**首个账号永远允许**（首次初始化）。守卫 = `AdminAuthFilter`（保护 `/api/admin/**`，豁免 `/auth/{login,register,status}` + OPTIONS 预检 + 静态资源；`auth.enabled=false` 完全关闭）；前端 = `views/Login.vue` + 路由守卫 + `api/http.js` 自动带令牌与 401 兜底 + 顶栏账号菜单（改密/退出）。错误码 40104/40105/40106/40301/40901。测试 19 例（`AuthIntegrationTest` 14 + `PasswordHasherTest` 5）+ 前端 9 单测 + 1 SSR。**账号管理与角色（RBAC 第一层，同日追加）**：侧边栏「账号管理」页 + `GET/POST /api/admin/users`、`PUT /{id}`、`POST /{id}/password\|{id}/unlock`、`DELETE /{id}`；列表带有效会话数、新建、启停用（停用即吊销会话）、重置口令（仅他人）、解锁、删除（需输入用户名确认）；**守卫只有两条底线**（v1 无 RBAC）：不能停用/删除最后一个可用账号、不能动自己（停用/删除/重置；判定顺序先「最后账号」后「自己」）；前端 `utils/users.mjs#accountGuard` 为客户端镜像。测试 +29（`AdminUserIntegrationTest` 11 + `AdminUserServiceTest` 12（Mockito 钉守卫）+ `AccountRulesTest` 6）+ 前端 6 单测 + 1 SSR。**角色**：`admin_user.role`（`OWNER`/`ADMIN`/`VIEWER`，默认 VIEWER 最小权限，首个账号=OWNER；已应用到开发库）；强制**只有两处**——`AdminAuthFilter`（VIEWER 非 GET → 40302；`/users/**` 需 OWNER/ADMIN → 40303）与 `AdminUserService`（ADMIN 不能删账号/改角色/动 OWNER；不能改自己角色；不能降级或删除最后一个 OWNER）；前端 `utils/roles.mjs` 镜像 + 路由 `meta.roles` + 菜单按角色隐藏 + 顶栏显示角色。测试 +14（`RoleRulesTest` 6 + `AdminUserServiceTest` +9 角色用例 − 1 合并 + 集成 +4）+ 前端 6 单测。设计：《开发文档/账号登录设计方案.md》（§13 账号管理 / §14 角色） |
| 真实接口联调修复（2026-09-18） | **FastMoss 前置编排真机跑通**（`POST /brief/create` → 前置 `fm` → `https://openapi.fastmoss.com/shop/v1/creatorList` → 宿主第三方）：① 修 `BearerTokenAuthAdapter` `prefix` 置空产生**前导空格**（改发裸 token + 5 单测）；② 修字段映射 source 下拉选不到 `steps.*`（新增 `utils/stepFields.mjs` + optgroup 分组 + 10 单测）；③ 定位并修掉 `code=1 params error` 的真实成因——**前置入参 = 宿主入站报文原样**，被调接口的 IN 声明不参与取值，必须由**被调接口自己的映射**适配（案例：`IF-FM-001` 补 5 条 rename，扁平 `seller_id` → 嵌套 `filter.seller_id`，可选字段用 `nullStrategy=NULL` 省略）；④ 「前置步骤」Tab 加常驻入参语义提示；⑤ `M1IntegrationTest` 两处**库态断言**改语义化（补映射/轮换凭证会让写死断言变红）。实测：`steps.fm.total=899` 且宿主映射消费成功（`creator_total=899` + `creators`）。详见《前置接口编排真实接口案例》§1.2.1/§3、《技术踩坑记录》§13 |
| 整体代码评审修复（2026-09-12） | **P1–P3 已修**（P0 安全项按指示暂不动：仓库内 DB 口令 / crypto key / `callback-allow-private` 默认 true）。P1：`calllog.dropped` 指标真正自增；主干红灯（SnapshotChangeDiffTest 夹具/断言）；M4 抖动（熔断窗口放宽 + c3 有界重扫 + 短路按 traceId 归属断言）；`prepare` 回填真实 id（KeyHolder）+ 唯一索引因 PolarDB=MySQL 5.7.28 不支持函数索引 → 官方降级为应用层保证（已写入 schema.sql/M5 手册）。P2：调用日志列表瘦身 + 新增 `GET /monitor/call-logs/{id}` 详情（前端抽屉按 id 拉）；关键字检索强制 ≤7 天窗口；apps/interfaces 列表 2000 上限；`ThreadPoolTaskScheduler(2)` 拆开补偿/告警 worker；删接口/删应用/删规则清理熔断·限流·告警内存态；`Interfaces.vue` 1458→1253 行（拆出 `InterfaceParamsTab.vue`）；公共 `CodeBlock.vue`/`utils/logContext.mjs` 去重；`RENDER_MAX_LINES` 20000→2000；`readBoolPref`；`mergeParams` 提纯函数 + 单测；去掉 Node 专属 `Buffer` 兜底。P3：`fingerprint` 短值不再回显明文；CallLogWriter 文案；长耗时端点 30s 超时；静默 catch 全部补日志；引入 ESLint（flat config）；文档计数口径统一 |
| 评审遗留补修（2026-09-18） | **D-PS-0 + P2-2.1 已修**：① 接口级读超时真实生效（新增 `config/PerRequestReadTimeoutFactory`，`UpstreamInvoker.dispatch` 作用域声明，`connect-timeout-ms` / `default-read-timeout-ms` 两项配置；单测 8 例 + 集成 3 例，含「模拟修复前行为必红」的反证）；② `timeoutMs` / `maxRetries` 值域校验（100~60000ms / 0~10，越界 40001；前端 `el-input-number` 同步 `:max`；集成 2 例含边界放行与默认值回读）。全库 206 @Test 全绿（2026-09-18） |
| 测试隔离修复（2026-09-18） | 追查「M4 `c5_死信重放` / StateChain `补偿重放_成功` 在全量套件下偶发红」（**单跑始终绿 6/6、8/8；全量约 5 轮中 2 轮红**）定位到三类隔离缺陷：① 4 个测试类未覆盖 worker `initial-delay`（=0 → 上下文启动即跑一轮**全局** `scan()`，与用例抢跑——2026-09-12 的「统一置 1h」其实只盖了 M2/M3/M4/StateChain）；② 两处 `deleteByApp` 裸按多态 `ref_id` 删死信（id 空间与另一方向重叠 → 误删）；③ 开发库残留 13 条孤儿死信 + 小 id 残留行被用例的全局 `scan()` 处理。已修：8 个测试类统一四属性置 1h、两处 `deleteByApp` 加 `biz_type` 过滤、清理孤儿行、`CompensationWorker` 耗尽告警补 `attempt/max/interface/biz_id` 诊断。修复后连续 2 轮全量 205 全绿（受成本约束未继续跑）；**根因链未 100% 闭合**（C5 那例的计数来源待新诊断行复现确认）——细节见《技术踩坑记录.md》§11 |
| 评审遗留 P2/P3 批次修复 + 编排可观测二期（2026-09-18） | 与编排同批落地（详见《前置接口编排设计方案.md》§0 末段）。**真 bug 2 个**：`AlertService.evictRule` 冷却键不匹配（`endsWith("#"+id)` vs 实际 `rule:<id>` → 删规则永不清理；已修 + `AlertServiceTest` 回归）；「死信编号」原为 `outbound_request.id`（照它 replay 会打错记录）→ `insertDeadLetter` 改回填真实 `dead_letter.id`（`M4IntegrationTest` 断言编号可查）。**健壮性**：`DeadLetterRepository` 全文参数化（原拼串可被反斜杠打乱字面量）；`MonitorService.reconcile/replayDeadLetter` 补 `@Transactional`；`GlobalExceptionHandler` 补 400（畸形 JSON/参数类型）·404（未匹配路径）·405（方法不支持）——原先三类全回 500；`AppService.base_url` 走 `CallbackUrlValidator`（格式 + SSRF 开关，与回调地址同一规则）；`path` 必须 `/` 开头、`upstreamPath` 拒空白等 URI 非法字符（否则运行期 `URI.create` 抛异常 → 500 + 熔断探针漏计数）；`CredentialRepository.findActive` 补 `ORDER BY id DESC LIMIT 1`；apps/interfaces 列表命中 2000 上限时 `log.warn`（不再静默截断）；`MonitorService.statsCache` 有界（appId 用户可控）；`CallLogWriter` 丢弃日志口径；`XmlProtocolAdapter` reader 显式 close；移除未使用的 MapStruct 依赖与处理器。**可观测二期**：`call_log.step_code`（前置调用的 OUT 条带步骤名 → Monitor「步骤」列 + 按步骤筛选）+ 前置响应体上限 `pre-step.max-response-bytes`（默认 256KB，超限按链失败拒绝、不截断）+ `/test` 弹窗「前置步骤」留痕表。**P3 收尾**：`MonitorService.downgradeExpiredUnknown` 改**逐行** `TransactionTemplate`（状态+审计同成败，不用方法级事务以免单行失败回滚整批）；深度超限（`DEPTH_EXCEEDED`）补 `PRE_STEP` 留痕节点（原先抛在任何留痕之前，监控页看不到原因）；`insertDeadLetter` 未回填主键（-1）时不给误导性「死信编号」。新增测试：`HttpErrorSemanticsTest`（4）。全库 **290 @Test**（针对性批次验证全绿：编排 15 / 错误语义 4 / M1 19 / 告警 10 / M2-M5+StateChain+MonitorStats+单测 60 + 账号登录 19） |
| 前置接口编排（2026-09-18） | **PS-1..PS-9 已落地**（《开发文档/前置接口编排设计方案.md》v0.1.3 + §0 落地记录表）：`interface_step`（**第 20 张表**，开发库已建）+ `PreStepExecutor`（绕开 OutboundEngine 状态机，只复用链/传输/熔断/短重试）+ `ResponseJudger`（信封+RESP 判定从 OutboundEngine 抽出共用）+ `ReservedKeys`（保留键 steps 两道剥离）+ `StateChainBuffer`（从 OutboundEngine 抽出批量通道）+ `ChainEngine` 三处小改（DECODE 可跳过 / MAPPING 前插前置 / ENCODE 前剥离）+ OutboundEngine 捕获边界 + **D-PS-11 补偿预算下限**（前置宿主 `max(2, maxRetries+1)`）+ 前端「前置步骤」Tab + `/test` chainTrace.steps 与步骤留痕表 + `offline` warnings + `call_log.step_code`（按步骤筛日志）+ 前置响应体上限；全库 **290 @Test**（编排相关 16：`PreStepIntegrationTest` 15 + 快照往返 1）。未做（按 §13/§14 边界）：CONTINUE/FALLBACK、overlay、条件执行、并行组、每跳超时覆盖、前端拖拽排序、`out_payload` 敏感值脱敏（与 P0 安全批次同做） |
| **XML 协议参数 B2（2026-09-21）** | **“与样本无关部分”已落地并真机验证**（《开发文档/B2完整SOAP开发计划.md》§8）：`xml.type` 为**唯一真相**（`POX`/`SOAP_1_1`/`SOAP_1_2`，缺省 POX ⇒ B1 旧数据零迁移），`soap` 段降为配置载体（action 可空 / envelopePrefix / unwrapResponse，**不含 version**）；`XmlProtocolAdapter` 支持 **SOAP 1.1/1.2 包裹与响应解包**（`ackMode` 保证 ack 不被包裹）；`SoapFaultParser`（新）+ `SoapClientFaultException`（**直接继承 `RuntimeException`**）+ `UpstreamInvoker` 5xx **带 body 抛** → `OutboundEngine`/`PreStepExecutor` 分类为 **`50203` 死信（不重试、不计熔断）**；前端「XML 类型」单选 + 按类型切子项。**真机实测**（真实 SOAP：NumberConversion/LearnWebServices）：1.1/1.2 成功 + 解包 + `VersionMismatch` → `50203`/零重试/熔断 CLOSED/P命中 `outcome=upstream_fail`；**全量 351 测试全绿**。**未完成**：企业级 `Header`/WS-Security 样本（G1）、`使用教程`/`整体测试方案`/`CLAUDE.md` 文档同步 |
| **入站鉴权 B1（2026-09-23）** | **《入站鉴权设计方案》v1.1 第一批「数据与目录」已落地**：DDL 3 表（`client_app` / `client_credential` / `access_auth_log`，**第 23/24/25 张**，开发库已建）+ `CredentialOwner` 枚举 + `CredentialRepository` 按属主列参数化（应用侧入口全保留为委托，**零回归**）+ **抽 `CredentialStore`**（机制）+ 两个语义入口（`CredentialService`（应用）/ `ClientCredentialService`（调用方））+ `ClientAppRepository`/`ClientService` + `ClientApi`（`/api/admin/clients/**` CRUD + 启停用 + 凭证子资源，与 `/apps/{appId}/credentials` 同形）+ 删适配器时同步清 `client_app.auth_adapter_id`。**出口标志全达**：调用方 CRUD ✓、凭证建/轮换（`prepare` 明文仅回显一次 + 遮显尾 4 + 库中密文）✓、停用生效 ✓。测试：`ClientAuthIntegrationTest` **9 例** + 回归 `M1IntegrationTest(19)` / `HmacCallbackVerifyAdapterTest(10)` 全绿（M1 表数断言 22 → 25 同步）。**未做（后续批次）**：B2 鉴权内核（`InboundAuthAdapter` + 4 impl + `ClientAuthVerifier`）/ B3 闸门与审计 / B4 管理面页面与可观测 / B5 文档验收 |
| **入站鉴权 B2（2026-09-23）** | **《入站鉴权设计方案》v1.1 第二批「鉴权内核」已落地**：`InboundAuthAdapter` 契约（`method()`/`credentialKind()`；**适配器不查库** —— 凭证明文由闸门经 `ctx.attrs("inboundCredentials")` 注入，D-CA-6）+ 4 impl（`ClientApiKeyVerifyAdapter` / `ClientHmacVerifyAdapter` / `ClientBearerVerifyAdapter` / `ClientIpWhitelistVerifyAdapter`，已进 `AdapterImplCatalog`）+ `ClientAuthVerifier`（§6.1 五步 + **步骤 ⓪**：`OPTIONS` 豁免 / `X-Internal-Token` → `PLATFORM_SELF`）+ `ClientAuthProperties`（`mode=OFF\|OPTIONAL\|ENFORCED`，默认 OFF）+ `InternalCallToken`（启动随机生成、仅存内存、常量时间比较、**空令牌不豁免**）+ `AccessAuthContext`（审计上下文，B3 落库）+ `SensitiveDataMasker.registerHeader` **动态注册**（D-CA-13 硬要求）。测试：`ClientAuthVerifierTest` **23 例**（§6.2 **真值表逐行** + 反证「不回退 Noop」+ 内部令牌 + IP 维度 + 审计字段）、`InboundAuthAdaptersTest` 10 例（含 **HMAC 与回调验签同口径**）、`SensitiveDataMaskerTest` 12 例；回归 `HmacCallbackVerifyAdapterTest` 全绿。**未做**：B3 闸门接入与审计落库（含 D-CA-17 自调带头）/ B4 管理面页面与可观测 / B5 验收 / B6 可选 |
| **入站鉴权 B3（2026-09-23）** | **《入站鉴权设计方案》v1.1 第三批「入口与审计」已落地**：`GatewayController` 在**路由命中后、引擎之前**执行闸门（**按 `if_type` 二分**：`OUTBOUND` 走调用方鉴权「拒绝不落 `outbound_request` ⇒ 不污染状态机」；`INBOUND` 保持链内回调验签）+ **回调方向审计登记**（主体=供应商 · 方式=绑定的验签适配器 · IP/UA）+ `InboundEngine` **401xx 回填 REJECT**（非 401xx 保持 PASS —— 鉴权确实通过了）+ `AccessAuthLogRepository` / `AccessAuthLogWriter`（异步批量，仿 `CallLogWriter`：有界队列 1000 + 丢弃计数 + 50 条/1s 批量）+ **网关切面 `finally` flush**（`AccessAuthContext` 读取后投递并清理，与 `CallLogContext` 同契约）+ **D-CA-17 落地**：`InterfaceController.testCallback` 自调网关时带 `X-Internal-Token`（**否则 `ENFORCED` 一上线「模拟回调」会被自家闸门打死**）。测试：`ClientAuthGateIntegrationTest` **5 例**（裸调 401 + **运行表零增量** + 审计 40107；正确凭证 200 + 审计 PASS 含名称/方式/IP；错误凭证 40100；内部令牌 PLATFORM_SELF；回调 `direction=CALLBACK` + 401xx 回填 REJECT/40100）；回归 `HttpErrorSemanticsTest`(6) + `M3IntegrationTest`(14) + `MonitorStatsIntegrationTest`(1) 全绿。**未做**：B4 管理面页面（`Clients.vue` + Monitor「接入鉴权」Tab）与指标告警 / B5 文档与验收 / B6 可选 |
| **入站鉴权 B4（2026-09-23）** | **《入站鉴权设计方案》v1.1 第四批「管理面与可观测」已落地**：**后端**：`GET /monitor/access-logs`（按主体 / IP / 结果 / 方式筛，时间窗 ≤7 天纪律同 call-logs）+ `GET /monitor/access-logs/summary`（近 24h：通过 / 拒绝 / Top 拒绝原因 / 未识别主体）+ 指标 `apicenter.gateway.auth{direction,principal,method,result}` / `apicenter.gateway.auth.latency` / `apicenter.auth.unknown_principal` + 告警 `AlertService.recordAuthFailure`（5 分钟窗口**按主体**计数、主体未知按 IP，命中阈值落 `alert_event(metric=auth_fail_streak, level=CRITICAL)`，文案含主体名+IP+最后错误码）+ 结构化单行日志（`鉴权结论 result=… principal=… ip=… method=… code=… reason=…`，**不打凭证/签名**）。**前端**：新增 **「调用方管理」页**（`views/Clients.vue`：列表 + 新建/编辑 + 启停用 + 删除 + **凭证管理**（生成明文仅回显一次 / 激活 / 完成轮换 / 吊销 / 删除）+ `isReadOnly(role)` 隐藏写操作）+ 路由 `/clients` + 菜单「◫ 调用方管理」；**监控页新增「接入鉴权」Tab**（摘要卡 + 主体/IP/结果/方式筛选 + 分页表）。测试：`AlertServiceTest` +2 例（`auth_fail_streak` 触发与窗口重置、按主体分桶不与回调告警冲突）；前端 `npm test` 125 单测 + **SSR 22/22**（+调用方管理页 1 例）、lint 0 警告、build 通过。**未做**：B5 文档与验收（§17 的 8 处同步 + seed 演示调用方 + 手动验收 + 灰度手册）/ B6 可选（回调验签契约迁移） |
| **入站鉴权 B5（2026-09-23）** | **《入站鉴权设计方案》v1.1 第五批「文档与验收」已落地**：**§17 的 8 处文档同步**（设计方案总纲 §1.2/§3.1/§5.3 口径改写 + §6.2 错误码 **+40107/40108**；时序图出站时序与图注（"组件范围外 Noop 占位"→"网关闸门已实现"）；项目说明 §2 边界 →「**做鉴权、不做授权**」；使用教程 **§4.4「调用方管理」**（四种方式对照 + 凭证轮换 + fail-closed 提示 + 应急回退两种粒度）+ §9.1 带鉴权头 curl 与**三步取证**；CLAUDE.md 表计数/页码/区块数 + 4 条 Gotchas；整体测试方案 **§6.7 A 组 13 例** + **R7**，R1–R6 引用同步为 R1–R7）+ **seed 演示调用方**：`DEMO-CLIENT` + `ADP-401`（调用方 API Key 验签）+ 固定演示密钥（幂等、与 fastmoss 种子**解耦**，在 `importSeed()` 早期补齐 ⇒ 增量导入场景也生效）+ **新增《开发文档/入站鉴权手动验收测试方案.md》（v1.0，页面操作版，约 25 分钟）**：四阶段（观察 / 配置与灰度 / 强制与联动 / 回调与平台自调）+ **灰度操作手册（S0–S4 + 两条硬要求：禁跳步、进 ENFORCED 前需逐个调用方"不带凭证存量 = 0"）** + 10 条常见误判 + 分工表。**未做**：B6（可选）回调验签契约迁移；**全库测试由用户手动执行**（测试执行纪律） |
| 里程碑计划 | **M4 手动验收（方案已细化，2026-09-05）待完成；M5.3 压测执行（方案与脚本已就绪，见《M5压测报告.md》，执行后回填数据）+ M5 手动验收待排期**——M5 开发计划已评审定稿（2026-09-04 一轮 + 09-07 二轮），D-M5-1~3 即编码依据，总盘 9 人日 |
| **XML 协议参数 B1（2026-09-21）** | **已落地并验证**（《开发文档/XML声明配置设计方案.md》v4.4 §14）：`interface.protocol_params`（**JSON 列，无新表**）→ 可配 XML 声明 `version`/`encoding`、根元素 `root`、命名空间 `namespace{prefix,uri}`；`XmlProtoConfig`（解析+白名单+NCName/URI 校验）、`ChainEngine` 装配期烘焙进缓存链 + `decodeResponse` 补传、`XmlProtocolAdapter.writeRoot`（三参 API 写命名空间）、`AckRenderer` 只取 version/encoding、前端 Interfaces「高级 → 协议参数」区 + `utils/protocolParams.mjs`。**协议参数区只看 `protocol_out`**（入站不解包 ⇒ in=XML/out=JSON 时不给入口，避免“配了不生效”）。**零回归**：未配置 = 内置默认（`1.0`/`UTF-8`/`request`/无 ns）= 改造前逐字节一致。**全量 325 测试全绿** + 前端 lint/test/build + 真实 USGS XML 端到端实录。**`encoding` 语义 = 声明可配**（非 ASCII 转字符引用，字节恒 ASCII 安全；**产不出原生 GBK 字节**，Woodstox 实测做不到）。`soap` 段属 **B2（完整 SOAP）—— 延后**（待真实 SOAP 供应商样本）；B2 已定：**`xml.type` 显式声明 `POX`（默认）/ `SOAP_1_1` / `SOAP_1_2`**（UI 标签：普通 XML（POX）/ SOAP 1.1 / SOAP 1.2），`soap` 段降为其配置载体且不含 version。 |
| 未拍板决策 | 无（M0 全部评审通过；M4/M5 计划均已评审定稿） |

## 设计文档（现行）

`src/main/resources/doc/`（6 份，互相引用闭环，改动需同步）：

| 文档 | 内容 |
|---|---|
| `API中心设计方案.md` | 设计总纲：应用（供应商）/ 分组 / 接口 / 监控 / 适配器 5 模块；接口定义模型（出站中转 / 入站回调）；三类适配器（鉴权 / 协议 / 报文）+ 接口级字段映射；状态机 / 错误码 / 容错附录 |
| `技术架构和实现方案.md` | 实现路径：分层架构、技术选型、适配器链引擎、出 / 入站执行引擎、M1–M5 路线图、ADR |
| `可行性报告.md` | 技术可行性评估、工作量估算（约 81 人日）、风险与应对 |
| `表结构设计.html` | **25 张表**（配置 12 + 运行 8 + 管理面账号 2 + **入站鉴权 3**；M4 增 reconcile_audit / alert_event，M5 后增 outbound_request_state_log，前置编排增 interface_step，账号登录增 admin_user / admin_session，入站鉴权增 client_app / client_credential / access_auth_log）+ 枚举汇总 + 原型数据模型映射对照 |
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
| `账号登录设计方案.md` | **管理面账号登录（v1.0，2026-09-18 已落地）**：只做认证不做权限；D-AUTH-1..10 决策（不透明令牌 vs Cookie/JWT、PBKDF2、惰性续期、注册与首次初始化、锁定、`login()` 不加事务）、表结构（第 21/22 张）、6 个端点 + 错误码、守卫与豁免、安全口径（含未做加固）、配置项、测试 19 例、坑 4 条、验收速查 curl、后续 RBAC 清单 |
| `入站鉴权设计方案.md` | **平台入站鉴权（调用方鉴权 · 回调验签 · 接入审计）v1.1（**待评审 · 未实施**）**：把设计里那句「调用方鉴权由平台统一处理、不在接口模型内」落地成范围内 —— 新增 `client_app`/`client_credential`/`access_auth_log`（第 23/24/25 张表）+ 网关入口**闸门**（D-CA-1..17）+ 三态灰度 `client-auth.mode`（默认 OFF，fail-closed）+ 鉴权方式复用 `adapter(auth)` + 审计「主体·IP·方式·结果」90 天；**v1.1 复核重点**：**平台自身调用必须豁免闸门**（「模拟回调」`/test-callback` 是**自调网关**，`ENFORCED` 下会被自家闸门拒 ⇒ 内部令牌 `PLATFORM_SELF` 豁免；含 `OPTIONS` 预检、前置编排、worker 三类内部路径清单） |
；**v2.0 关键更正**：**「测试接口」绕过网关（直测不出鉴权、无审计）** ⇒ 本版一律改用**平台对外路径**；**「模拟回调」是自调网关 ⇒ 会过闸门**（阶段四专验 D-CA-17 内部令牌豁免）。含附 A 可复制 curl 全集 / 附 B 打印用检查清单 / 附 C 取证四步 / 附 D 常见误判 12 条| `入站鉴权手动验收测试方案.md` | **入站鉴权手动测试 Runbook（v2.0，**逐步骤·可照做**，约 30 分钟）**：§0 前置（**怎么切 `client-auth.mode` + 重启**；运行期开关=单调用方停用）→ 阶段一观察模式（OFF+审计，S0 存量盘点）→ 阶段二配置与灰度（建调用方/凭证/激活 + OPTIONAL 三态）→ 阶段三强制与联动（ENFORCED：`40107` 且**运行表零新增**、traceId 串联、筛选、停用即时生效、**`40108` fail-closed**、IP 维度、`auth_fail_streak` 告警）→ 阶段四回调与平台自调（**D-CA-17：模拟回调在 ENFORCED 下仍可用**）→ §5 常见误判 10 条 → §6 灰度手册 S0–S4 → §7 收尾回归 → §8 自动化↔手动分工 |
| `M5手动验收测试方案.md` | M5 手动端到端验收（按 M5 实施后实际行为校准）：三阶段 = 版本快照与回滚 / 灰度绑定与即时生效 / 压测与总巡检，见 `src/main/resources/doc/开发文档/M5手动验收测试方案.md`                                                                                                |
| `M5压测报告.md` | **M5.3 性能测试执行手册 + 报告（一份两用，现状 ⬜ 五场景执行待排期）**：环境与拓扑 / 放大点清单（读放大已烘焙、写放大含状态链）/ 指标口径与 Prometheus 速查 / 偏差控制 / 脚本与造数红线 / 五场景（含数据回填表与断言）/ 执行流程约 2h / 调优台账 / 同步送达决策矩阵 / 风险边界 / 退出检查表；脚本 `src/test/resources/m5-load/`。执行后回填 §1 摘要并同步《M5开发计划.md》§6 第 4/5 项 |
| `XML声明配置设计方案.md` | **XML 声明/根元素/命名空间/SOAP 设计与落地记录（v4.3，**B1 已落地**）**：XML 输出能力 12 探针 + 真实 SOAP 4 项实测 + 代码事实 C1~C5；接口级 `interface.protocol_params`（**JSON 列，无新表**）；D-XD-1~12 / D-SOAP-1~9；`encoding` 语义 A（**声明可配，非 ASCII 转字符引用**，产不出原生 GBK/UTF-16 字节）；4 个调用点；Q1~Q19 全部闭合；§14 B1 落地记录（改动清单/325 测试全绿/真实 USGS 端到端实录/实现期发现 I-1~I-4）；§13 backlog 11 项（带**触发条件**） |
| `B2完整SOAP开发计划.md` | **B2（完整 SOAP）执行计划（6.4 人日，**⏸ 延后待真实 SOAP 样本**）**：§1 开工 gate（G1 真实 WSDL/报文样例）+ 范围纪律（不做 Header/1.2 的 200+Fault/服务端 SOAP/ack SOAP 化/原生字节）；§2 线协议 1.1/1.2 对照 + Fault 分类 + **四条必照抄的实现约束**；§3 T1~T11 任务拆解与验收点；§4 用例矩阵 12 项 + 4 条反证 + worker 隔离纪律；§5 手动验收三阶段（`dneonline` 免密钥技术验证 / 真实供应商验收 / 灰度回归）；§6 出口检查表；§7 风险 R1~R6 + **与 B1 的衔接清单** |
| `协议参数手动验收测试方案.md` | **协议参数手动验收（v3.0，五阶段约 30 分钟，**页面操作版/零 curl**）**：B1 四阶段（保存期输入约束 / 出站报文取证 / 边界与解耦 / 即时生效·回滚·复制）+ **B2 阶段五（SOAP 1.1/1.2 包裹·解包·响应解包开关·Fault 分流·类型切回 POX 与旧数据零迁移）**；§6 常见误判表（**24 条**）；§7 收尾（含 SOAP 造数与死信清理）+ `clean test` 回归（**352**）；§8 自动化↔手动分工 |
| `端到端闭环演示方案.md` | **演示脚本（界面配置 → 真实供应商 / 真实 XML → 运维回归）**：串讲《整体测试方案》§1.5/§4/§5/§6/§6.5(evoLink)/§6.6(真实 XML)/§7/§8/§10 成一条叙事线，每环节给「操作 / 原理 / 设计思路 / 实现方式 / 自检」 |
| `前置接口编排真实接口案例（FastMoss + evoLink）.md` | **前置编排的真实接口手动测试（v1.0，2026-09-18）**：案例 A = FastMoss 达人列表作前置（信封+RESP 白名单下的 `steps.fm.total` / `steps.fm.list` 整块引用、condition 守门、宿主字段不被污染、调用日志 `step_code` 筛选、B 不落独立运行记录、前置不经接入层防护的可判别断言）；案例 B = evoLink 建任务作前置取 `taskId` 再交第三方（异步语义边界：完成由回调/轮询承担）；含「先抓真实响应锚定字段名」、无凭据 stub 回放（真实报文快照）、M0-02 D1 数组下标限制的正解与踩坑表、收尾 SQL 与退出检查表 |
| `前置接口编排设计方案.md` | **接口编排（既有接口复用为前置，A → B → 第三方）方案（v0.1.3 评审稿，待拍板，未实施；前置条件 PS-0 已落地、D-PS-11 已拍板）**：OUTBOUND 接口挂 `interface_step` 配置子表，链内 3 处小改（DECODE 可跳过 / MAPPING 前插前置 / ENCODE 剥离保留键 `steps`）+ `PreStepExecutor` + `ResponseJudger`（判定逻辑抽取）+ OutboundEngine 捕获边界改造（运行期零 DDL）；含失败传播矩阵（含 `max_retries=0` 决策与基线用例）/ 幂等与 trace / 前端「前置步骤」Tab 界面设计 / 改动面清单 / D-PS-1~12（11 已拍板） |
| `整体测试方案.md` | **全项目测试总纲（v2.0）**：§1.5 速通（一键准备+黄金链路冒烟）+ 用例库（C 配置 / F 出站 / B 入站 / O 可观测 / V 版本·变更说明·复制 / 自动化全库）/ 回归矩阵 R0-R6 / reset-dev.sql 清理 / 工具箱；执行入口见 `src/main/resources/doc/开发文档/整体测试方案.md`         |

关键设计要点（改动前先读设计方案对应章节）：

- **应用 = 供应商**：出站凭证（供应商签名）+ 回调验签凭证两类分离；调用方鉴权 / 向回调地址签名由平台统一，不在模型内（§1.2 / §3.1 / §5.3）。
- **接口两种类型**：出站中转（供应商接口路径 + 供应商签名 + 出站响应字段）/ 入站回调（回调地址 + 回调验签 + 出站侧送达报文必填 + ack 回执字段）；类型互斥字段按类型清空（§3.1）。
- **适配器三类**：鉴权 / 协议 / 报文；字段映射为接口级配置（不是适配器）；协议适配器按接口协议自动推导、不参与绑定；绑定角色 = 报文 / 供应商签名 / 回调验签，应用级默认 + 接口级覆盖（§5.1 / §5.7）。
- **字段映射**：运行时规则（source/op/target/param/nullStrategy，6 种操作），非编译期映射（§5.6）。
- **无平台侧幂等开关**：去重依赖供应商对业务键幂等（§6.3）。
- **入站 ack = 回执**：收到即回、与送达解耦，无「调用方 ack → 供应商 ack」反向映射（§5.5 / §6.1）。

> **管理面鉴权（2026-09-18）**：`/api/admin/**` 需要登录（不透明 Bearer 令牌，只做认证不做权限）；**平台对外接口路径 `/{platformPath}` 不受影响**——调用方鉴权是另一特性（设计 §1.2 / §5.3）。

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

> 🚫 **测试执行纪律（2026-09-22 用户定，必须遵守）**
>
> **默认禁止：`mvn -o clean test`（全量 + clean）。**
>
> | 场景 | 该怎么做 |
> |---|---|
> | 改了后端代码，要验证 | **只跑相关测试类**：`mvn -o test -Dtest=XxxTest,YyyTest`（**不 clean**；必要时加 `-DfailIfNoSpecifiedTests=false`） |
> | 只确认能否编译 | `mvn -o test-compile`（**不加 clean**） |
> | 改了前端 | `cd frontend` → `npm test` / `npm run lint` / `npm run build`（这三条**允许直接跑**） |
> | **需要全量测试、或需要 clean** | ⛔ **不要自己跑** —— **停下来告知用户，由用户手动执行** |
> | **结构变更**（新增 / 删除 / 重命名源文件、改包名或注解） | 增量编译会**假通过**（旧 class 残留在 `target/classes`）⇒ 必须 clean ⇒ **告知用户手动跑 `mvn -o clean test`** |
>
> **理由**：全量 `clean test` 成本高（数百例集成测试连远程 PolarDB，数分钟起），日常改动用"小而准"的相关测试类足够。
> 另外：**手动验收 / 起实例期间不要跑任何 `mvn test`**（WireMock 占 `18080`，与集成测试同端口）。

## 架构与源码结构

根包 `com.deepx.apicenter`（`src/main/java/com/deepx/apicenter/`），按技术架构分层规划（M1 起逐步落地）：

| 包 | 职责 | 落地里程碑 |
|---|---|---|
| `controller/` | 管理面 REST（应用 / 分组 / 接口 / 监控 / 适配器 5 模块 + **账号 `auth`**）+ 接入层路由 | M1 / M2 / M4（监控 + 死信重放 + 对账端点）/ 账号登录（2026-09-18） |
| `service/` | 业务编排：配置校验、状态机流转、接入层防护（GatewayGuard）、账号认证（AuthService + PasswordHasher） | M1 / M4 / 账号登录（2026-09-18） |
| `repository/` | JdbcTemplate 数据访问（25 张表） | M1 / M4（reconcile_audit / alert_event）/ M5 后（state_log）/ 前置编排（interface_step）/ 账号登录（admin_user · admin_session）/ **入站鉴权（client_app · client_credential · access_auth_log）** |
| `engine/` | 适配器链引擎 + 出站 / 入站执行引擎 + 熔断器（CircuitBreakerRegistry） | M2 / M3 / M4 |
| `adapter/` | 鉴权 / 协议 / 报文三类适配器实现 | M2 |
| `mapping/` | 动态字段映射引擎（M0-02 规范，6 操作运行时解释器） | M2 |
| `client/` | 通用声明式 HTTP 客户端（M0-03 契约，动态 URI / 凭证组装） | M2 |
| `worker/` | 补偿 / 对账 / 告警 worker（按 (status, next_retry_at) 扫描）+ call_log 异步写 | M2 / M3（入站重送）/ M4（TTL 降级 + 告警） |
| `aspect/` | AOP 调用日志、traceId、脱敏（SensitiveDataMasker） | M4（已落地） |
| `config/` | 配置与 Bean 装配（含 `AdminAuthFilter` 管理面鉴权过滤器） | M1 / 账号登录（2026-09-18） |

入口：`ApicenterApplication.java`（`@SpringBootApplication` + `@EnableScheduling` + `@EnableResilientMethods`，后者启用 Spring 7 `@Retryable`）。

前端 `frontend/`（Vue3 + Vite + Element Plus，M1 设计 §4）：`src/views/` 八页面（Dashboard / Apps / Groups / **Clients 调用方管理** / Interfaces / Adapters / Monitor / Users 账号管理）+ `views/Login.vue`（登录/注册）+ `components/ParamTable` 参数编辑 + `components/CredentialEntry` 凭证卡片 + `components/PayloadViewer` 报文美化展示 + `components/ParamImportDialog` 参数快速导入 + `components/InterfaceStepsTab` 前置步骤编排（编排落地） + `utils/payload.mjs`（JSON/XML/form/头串扫描式缩进与 tokenizer，只增删空白）+ `utils/paramImport.mjs`（JSON→参数行推断，示例值取 token 原始切片）+ `utils/stepFields.mjs`（前置步骤输出 → 字段映射 source 分组）+ `utils/auth.mjs`（登录态：令牌存取/校验/防开放重定向）+ `utils/users.mjs`（账号管理操作许可镜像）+ `utils/prefs.mjs`（行号·折行·抽屉宽度记忆）+ `workers/payloadFormat.worker.mjs`（>256KB 后台格式化）+ `api/http.js` 统一信封解包；前端 `npm test` = 单测（Node 内置 test runner，83 例）+ 组件 SSR 冒烟（`test/ssr-smoke.mjs`，18 例）；`npm run lint` = ESLint（flat config，`--max-warnings 0`）。原型交互平移自 `doc/API中心原型.html`。管理面 REST 前缀 `/api/admin`（controller/admin 八个 Controller：应用 / 分组 / 接口 / 适配器 / 凭证 / 监控 / 认证 / 账号管理），统一信封 `{code, msg, data}`。Monitor 页 M4 已接真数据（统计卡 / 调用日志 / 对账 UNKNOWN / 死信 / 告警五区块）。

## 核心状态机与容错（设计 §6）

- **出站状态机**（载体 `outbound_request.status`）：`INIT → MAPPING → SENDING → RETRYING → COMPENSATING → SUCCESS / DEAD_LETTER / UNKNOWN`。
  - 5xx/429 → 短重试（`@Retryable`，指数退避，上限 `interface.max_retries`）；重试耗尽 → 补偿
  - 4xx（非 429）→ 写 `dead_letter` → DEAD_LETTER，不重试
  - 读超时 / 连接异常 → UNKNOWN → 对账（M2 人工 / M4 超时自动降级，见 M0-03 §3）
  - **`@Retryable` 必须放在独立 Invoker 类**——Spring AOP 自调用不触发代理
- **入站送达状态**（载体 `inbound_delivery.delivery_status`）：`RECEIVED → ACKED / PENDING → ACKED / DEAD_LETTER`；送达失败仍回 ack（供应商不重发），补偿 worker 重送（按 `callback_url_snapshot`）。
- **熔断（M4 已落地）**：三态 CLOSED/OPEN/HALF_OPEN，闸门置于 @Retryable Invoker 调用前，粒度「接口 + 供应商」；计数口径 = 每请求一次（@Retryable 内部重试不逐次计数）；OPEN 短路转 COMPENSATING 顺延（不 incrementAttempt），恢复后由补偿 worker 补做。
- **UNKNOWN 对账（M4 已落地）**：人工置位（SUCCESS / COMPENSATING，写 reconcile_audit，source=MANUAL）+ TTL 10 分钟自动降级（source=TTL）两来源审计；对账自动查询 v1.1（M0-03 C3）。
- **链失败 / 防护拒绝均不污染状态机**（M0-01 D7 / D-M4-6）：解码 / 映射 / 编码 / 验签失败直接错误响应 + call_log，**记录停留 `INIT`**（不推进状态机、无终态/无死信/不入补偿——注意不是「不落运行表」，`createRecord` 在链执行之前）；QPS / 日配额 / IP 名单在 GatewayGuard 防护层拒绝（42901 / 42902 / 40103），同样不落运行表、不进状态机。
- **补偿预算语义（2026-09-18 固化，易踩）**：`max_attempts = max_retries + 1`，而 `attempt_count` **首送即 1**（`createRecord`）⇒ **`max_retries=0` 的接口一旦进 `COMPENSATING`，首次扫描即判「补偿耗尽」→ `DEAD_LETTER` + `trigger=EXHAUSTED`，零补偿尝试**（`UNKNOWN` TTL 降级与死信重放会把 `attempt_count` 清零 = 给新预算）。已固化：`StateChainIntegrationTest#maxRetries0宿主_5xx进补偿后首次扫描即耗尽死信_零补偿尝试`（用 WireMock 零请求断言钉死「零补偿尝试」）。《前置接口编排设计方案》D-PS-11 据此拍板：**前置宿主强制补偿预算 ≥1**（落地时点 = 其 PS-4）。

## 配置与数据模型

- 配置集中在 `src/main/resources/application.yaml`：仅基础设施参数（datasource、`retry-worker-fixed-delay-ms: 3000`、`unknown-ttl-minutes: 10`、`auth.*` 认证参数）；业务配置（应用 / 接口 / 适配器 / 字段映射）全部落库。认证配置：`auth.enabled/allow-register/session-ttl-hours/renew-interval-minutes/max-failed-attempts/lock-minutes`（账号与会话本身落库：`admin_user` / `admin_session`）。
- `src/main/resources/doc/schema.sql`：**25 张表**（配置 12 + 运行 8 + 管理面账号 2 + **入站鉴权 3**；M4 新增 reconcile_audit / alert_event + idx_outreq_updated，M5 后新增 outbound_request_state_log + adapter.name 唯一，前置编排新增 interface_step，账号登录新增 admin_user / admin_session，**入站鉴权新增 client_app / client_credential / access_auth_log**），无数据库外键（引用完整性应用层保证，引用列建索引），与《表结构设计.html》逐表一致。

## 约定与注意事项（Gotchas）

- **管理面已启用账号登录（2026-09-18）**：`/api/admin/**` 全部需要 `Authorization: Bearer <token>`（豁免 `/api/admin/auth/{login,register,status}`、`OPTIONS` 预检、静态资源与 `/actuator/health`）。
  影响三处写法：① **curl / 文档示例**必须先登录取 token（《使用教程》§8.3）；② **集成测试**里直连管理面 HTTP 的类（`HttpErrorSemanticsTest` / M3 / M4 / M5 / `MonitorStatsIntegrationTest`）在 `@SpringBootTest(properties=...)` 中置 `app.api-center.auth.enabled=false`（它们不测认证），认证本身由 `AuthIntegrationTest` 用默认值覆盖；③ **新增管理面端点无需改任何东西**（过滤器按前缀统一拦），但新增**豁免**路径要显式加到 `AdminAuthFilter.EXEMPT`（且要想清楚：豁免 = 匿名可访问）。
  `auth.enabled=false` 是唯一总开关（应急回退/本地调试）；`allow-register=false` 时仍允许「首个账号」初始化。设计见《账号登录设计方案.md》。
- **`AuthService.login()` 刻意不加 `@Transactional`（真坑，别加回去）**：登录失败要抛 `BizException`，同一事务会把「失败计数 +1」一起回滚 → 连续失败次数永远停在 1，**锁定形同虚设**（被 `AuthIntegrationTest#连续失败达阈值_锁定且正确密码也被拒` 抓到）。同类通用结论：**「先写库、再抛异常」的流程不要挂事务**（或把写库放 `REQUIRES_NEW`）。
- **角色（RBAC 第一层，2026-09-18）**：`OWNER` > `ADMIN` > `VIEWER`（默认 VIEWER = 最小权限；首个账号自动 OWNER）。**强制只写两处**：`AdminAuthFilter`（VIEWER 非 GET 管理面请求 → 40302；`/api/admin/users/**` 需 OWNER/ADMIN → 40303）与 `AdminUserService`（语义级：ADMIN 不能删账号/改角色/操作 OWNER，不能改自己角色，不能降级或删除最后一个 OWNER）。新增管理面写端点**不用改任何权限代码**；但要新增「角色相关入口」时，务必同步三处：`RoleRules`（判定源）、`utils/roles.mjs`（前端镜像）、路由 `meta.roles` + 菜单 `v-if`（否则界面露出「点了必 403」的入口）。**角色变更会吊销该账号会话**（避免旧权限残留）。
- **账号管理（2026-09-18）的三条安全底线**：① 不能停用/删除**最后一个可用账号**（`countEnabled()<=1`）② **不能动自己**（停用/删除/重置口令；改显示名允许）③ 不能降级/删除**最后一个 OWNER**、不能改自己角色。①②判定顺序固定「先①后②」（单账号环境下提示更贴切）。新增账号管理类端点时：守卫写进 `AdminUserService`（服务端权威），前端 `utils/users.mjs#accountGuard(row, meId, meRole)` 只做按钮禁用镜像。
- **`AdminUserService.guardLockout(operatorId, target, action)` 的第一个参数是操作者**（真 bug 曾被抓到）：调用处一度把 **target id** 当 operatorId → 判成「操作自己」→ 停用他人永远被拒。两个参数都是 `long`、极易传反，改动时务必连带复核审计日志里的 operator（回归：`AdminUserServiceTest#停用他人_改状态并吊销其全部会话`）。
- **账号登录的存储与令牌口径**：口令只存 PBKDF2 摘要（`pbkdf2$120000$salt$hash`，`PasswordHasher`），**禁**任何接口/日志回显；令牌 32 字节随机、**库内只存 SHA-256 摘要**（`admin_session.token_hash`），明文只在登录/注册响应出现一次；改密吊销该账号其他会话（当前会话保留）、登出即删行。前端令牌在 `localStorage`（`utils/auth.mjs`，storage 可注入便于单测、**顶层必须做 `typeof localStorage` 守卫**否则 SSR 冒烟直接抛），`api/http.js` 遇 401/40104 清令牌并整页跳 `/login?redirect=…`。
- **中文注释**：全库代码注释、README、设计文档均为简体中文，新代码保持中文注释。
- **MapStruct + Lombok**：通过 `maven-compiler-plugin` 的 `annotationProcessorPaths` 显式配置（compile 与 test-compile 两个 execution）。MapStruct 只用于固定结构映射（统一信封组装、实体 ↔ DTO），动态映射走规则解释器（M0-02）。
- **Boot 4 不自动装配 `RestClient.Builder`**：手动构建 `RestClient` Bean（`RestClientConfig`）。
- **Spring 7 内置 @Retryable**（`org.springframework.resilience.annotation`，**不是 spring-retry**）：退避参数内联（无 @Backoff）、耗尽透传原异常（无 RetryExhaustedException）。**大坑：`maxRetriesString` 的 SpEL 只在方法首次调用时求值一次（MethodRetrySpec 按方法缓存），ThreadLocal + SpEL 动态次数方案不生效**——动态重试预算必须走 `@Retryable(predicate=...)` 扩展点 + 引擎 `beginRetryBudget/endRetryBudget` 包裹（实现见 `UpstreamInvoker`）。详见《技术踩坑记录.md》§1.1。
- **Spring 7 声明式客户端 URI 模板坑**：`@HttpExchange` 的动态完整 URL 模板变量会被路径编码（scheme 丢失）——**动态 URL 一律 RestClient 直调**（`uri(URI)`），且需 `defaultStatusHandler` 禁用默认 4xx/5xx 抛异常（引擎分类）。详见《技术踩坑记录.md》§3。
- **Jackson 3**：包名 `tools.jackson.*`；`JsonNode.fields()` 已更名为 `properties()`。
- **WireMock 3**：verify 用 `postRequestedFor(urlEqualTo(...))` + `equalTo(...)`；请求计数跨测试累积，`@BeforeEach` 需 `resetAll()`。
- **`mvn test` 不清旧产物**：删源文件后旧 class 残留在 target/classes 会被 Spring 扫描装配 ⇒ **结构变更必须 clean**；但按「**测试执行纪律**」（见《常用命令》）**clean 与全量测试一律由用户手动执行**，我只需**明确告知**（不要自己跑）。
- **列表接口不带子表**：断言/校验接口子表（params/mappings 等）必须走 `detail()`，`list()` 的子表恒空。
- **熔断 / 限流 / 日配额为单实例内存口径**：多实例部署各实例独立（v1.1 分布式，日配额重启清零）；QPS 为固定秒级窗口（交界突刺最坏 2×limit）；WireMock 占 18080 与集成测试同端口，手动验收期间勿同时跑 `mvn test`。语义详见《M4开发计划.md》。
- **M5 链缓存烘焙与事件失效**：绑定解析 / 映射规则 / 入站参数声明在链装配时一次解析烘焙进缓存链（凭证仍每请求实时读）——**配置变更后绑定/协议即时生效依赖 `ConfigChangedEvent` 事件失效**：InterfaceService（update/rollback/publish/offline/delete）→ INTERFACE 精准移除；AdapterService（增改/启停/删）、AppService（默认绑定/启停）→ ADAPTER/APP 全清；**新增配置入口必须补发事件**（漏一处最长 5 分钟不生效，TTL 兜底）。凭证轮换不进清单（每请求实时读，天然即时）。
- **前置调用的入参语义（2026-09-18 真实事故，务必先读）**：前置调用的入参 = **宿主入站模型原样**（`ReservedKeys.withoutSteps` + 跳过 DECODE），
  **被调接口的「入站参数」声明不参与取值**（它只描述「别人直调它」的契约）——把宿主扁平字段适配成第三方契约的责任在**被调接口自己的字段映射**
  （点路径 target 会自动建中间对象）。被调接口**映射为空 = 整体透传**（宿主多余字段也会外泄 → 第三方常见 `params error`）；**非空 = 白名单**。
  可选/多形态字段一律用 `nullStrategy=NULL`（**省略**）而非 `KEEP`（写 JSON null）；**`default` 写的是字符串**，别给数字字段补值。
  宿主映射**在前置之后**执行 ⇒ 宿主映射无法影响前置入参。调试口径：调用日志「步骤=xxx」那条的 `reqBody` = 发给第三方的实录（`step_code` 可筛）。
  回归：`PreStepIntegrationTest#前置接口映射把宿主扁平报文适配成第三方嵌套报文_白名单且缺席字段省略`；界面提示在「前置步骤」Tab 顶部；详见《技术踩坑记录》§13。
- **开发库 = 测试库：不要写死「seed 资产的库态」断言（2026-09-18 实测两处变红）**：给 `IF-FM-001` 补映射会让 `M1IntegrationTest` 的
  `mappings()).isEmpty()` 失败；真机轮换出站凭证会让 `hasSize(1)` 失败（M0-04：新→ACTIVE、旧→ROTATING 24h，再更新则 RETIRED）。
  写法：断言**语义**（映射「空或含联调目标」二选一；凭证「恰 1 条 ACTIVE，其余 ROTATING/RETIRED」），要写死就用测试自建夹具（如 M2 的 `goldenInterface(...)`）。
- **入站鉴权方式新增 4 impl（2026-09-23 B2）**：`ClientApiKeyVerifyAdapter` / `ClientHmacVerifyAdapter` / `ClientBearerVerifyAdapter` / `ClientIpWhitelistVerifyAdapter`（`adapter.type=auth`）。**它们既可被 `client_app.auth_adapter_id` 引用（调用方鉴权），也可绑到接口 `CALLBACK_AUTH` 角色（回调验签）** —— 校验逻辑与"是谁的凭证"无关，且**不查库**（凭证由调用方经 ctx 注入）。⚠️ 调用方方向 **fail-closed**：未配 / 停用适配器 → `40108`，**不回退 Noop**（与链内「逐层回退」语义相反，§6.5）。
- **适配器 D6'（2026-09-08 定稿，替换 M5 灰度版本矩阵）**：adapter.name 全表唯一；同 (impl, version) 允许多条启用并存（实例靠 id + name 区分）；多实例并行首选同 impl 不同 version；binding.version **不再路由**——绑定即实例（恒用绑定行 adapter_id），version 仅记录/留痕；目标实例缺失 / 停用 → 逐层回退应用默认 → Noop。
- **接口变更说明走 `X-Change-Note` 请求头**（不扩展 InterfaceRequest DTO）：随 PUT 保存生成新版本快照的 change_note；版本历史 / 回滚端点 `GET/POST /api/admin/interfaces/{id}/versions...`、回滚 body {targetVersion, operator, reason, currentVersion}（目标缺失 40403 / 乐观锁冲突 40001）。
- **test 端点响应已包装**：`POST /{id}/test` data 变为 `{chainTrace, result}`（D-M5-2 留痕通道 3，强制实时解析）；前端解析相应调整。
- **应用弹窗内联凭证（v0.2，2026-09-11 落地）**：凭证卡片在「新建/编辑应用」弹窗内，**显示条件** = 该角色选了非 `NoopAuthAdapter` 的适配器（或已有凭证，只读行）；**字段来源** = 所选 adapter 的 impl 元数据中 `kind==='secret'` 字段，未声明时退化为单「密钥 / Token」框（如 `HmacCallbackVerifyAdapter`）；**留空 = 不改动**，单字段→字符串 / 多字段→JSON 提交；新建应用凭证是 `POST /apps` 成功后**串行**写（失败提示后引导补填）；列表凭证角标取 `AppResponse.has*Credential`（E1）。设计文档：`doc/开发文档/应用凭证配置改造方案.md`。⚠️ **两个下拉是"角色对应"的**（供应商签名→`OUTBOUND` / 回调验签→`CALLBACK`）；2026-09-22 实测踩坑：把「HMAC 回调验签」选进**供应商签名** ⇒ 凭证静默存成 `OUTBOUND`、回调验签为空，直到点「模拟回调」才报 `应用未配置回调验签凭证（CALLBACK）`（报症状不报原因）⇒ 已按角色**过滤两个下拉**（`utils/adapterUsage.mjs#adapterMatchesRole`，未知 impl 两侧都放行）+ 下拉旁加提示。⚠️ **解绑 ≠ 删除**：清空角色适配器**不会**删该 `kind` 的凭证（凭证是独立资源、绑定只是引用；明文不可回显 ⇒ 不随解绑静默删除）——卡片会提示“已不再被使用 + 怎么清理”，真正删除是**两段式**：详情抽屉 → **吊销**（→ `RETIRED`）→ **删除**（`CredentialService.delete` 仅允许 `RETIRED`）。提示语判定抽在 `utils/credentialHint.mjs`（纯函数 + 单测）。
- **`CredentialRepository.countByStatus` 与 `countLiveRotating` 的区别**：前者含已过期 ROTATING 行，仅用于 `retire` 的 ACTIVE 计数；`prepare` 的「已有待激活轮换」判定必须用 `countLiveRotating`（排除 `rotating_until` 已过期，E2 修复）。
- **请求参数快速导入（2026-09-12）**：接口弹窗 →「请求参数」→ 每侧 `⇪ 快速导入参数`（`components/ParamImportDialog.vue` + `utils/paramImport.mjs`）。示例值取 **token 原始切片**（19 位数字等保真），`JSON.parse` 只用于校验；路径 = 嵌套 `.` + 数组 `[0]` + 特殊键名 `["a.b"]`；默认覆盖同名并追加 + 一次撤销快照；上限 200 条 / 深度 6。设计见 `doc/开发文档/参数快速导入设计方案.md`。
- **接口级读超时走作用域声明（D-PS-0，2026-09-13）**：`interface.timeout_ms` 经 `PerRequestReadTimeoutFactory.withReadTimeout(...)` 作用域在 `UpstreamInvoker.dispatch` 内声明——**这是 `Spec.readTimeoutMs` 唯一的消费点**；新增出站调用路径（如未来接入新客户端 / 新引擎）必须同样包裹作用域，否则静默退回全局 `default-read-timeout-ms`（**10000ms**，2026-09-22 起；此前 3000）。作用域是**栈式**（嵌套安全），`close()` 后清 ThreadLocal。连接超时全局 `connect-timeout-ms`（per-request 连接超时需 per-request HttpClient，不做）。
- **前置步骤（编排）保留键 `steps`（2026-09-18）**：宿主模型的步骤输出挂在保留命名空间 `steps.<stepCode>.<field>`，写入一律用 `ReservedKeys.putStep`（**字面量键**，禁走点路径解析）；两道剥离缺一不可——交前置接口前 `withoutSteps`（否则前置透传会把宿主步骤输出发给它的供应商）、宿主 ENCODE 前 `stripSteps`（否则宿主透传会把 steps 发给第三方）；同时保存期禁止把 `steps` 用做 IN 参数名 / 映射 target 名（**仅在该接口配了前置时拦截**，不惊动既有接口）。步骤留痕节点用 `from=to=INIT` + `trigger=PRE_STEP`（此刻 status 尚未进 MAPPING）。
- **前置编排的扩展纪律（2026-09-18）**：`PreStepExecutor` 直调 `ChainEngine` + `UpstreamInvoker`（**不**经 `OutboundEngine`，否则会重置状态链缓冲 / 覆盖 CallLogContext / 冲掉宿主预算 / 落可被 worker 独立重放孤儿记录）；它的调用受 `ChainEngine` 的 MAPPING 闭包内抛出 `PreStepFailure` → **必须由 `OutboundEngine.doInvoke` 捕获并分类**（`chainEngine.execute` 原本在 try 之外）；宿主预算下限由 D-PS-11 拍定（配了前置 ⇒ `max_attempts = max(2, maxRetries+1)`）；新增出站调用路径若要复用前置能力，须同样保证「捕获边界 + 预算 save/restore + preCallDepth 传递」三件事。
- **时间列写入统一走 `repository/SqlTimes.ts(...)`（2026-09-12）**：MySQL `DATETIME`(0) 会把带毫秒的值**四舍五入到秒**，直接 `Timestamp.valueOf(LocalDateTime.now())` 写 `next_retry_at` 会让「立即入队」变成「下一句才生效」（C3 用例随机挂）。新增写库的调度时间字段请复用该 helper；统计窗口上界则相反——`statWindow.to` 不要 `withNano(0)`，否则本秒写入的日志被半开区间排除（TOP 偶发少一条）。
- **集成测试隔离后台 worker（2026-09-12 起，2026-09-18 补齐）**：`retry-worker-initial-delay-ms` / `alert-worker-initial-delay-ms` 默认 **0 = Spring 上下文启动即跑一轮 `scan()`**，而 `scan()`（`findDueCompensating` / `downgradeExpiredUnknown`）**不按应用过滤**——会处理**其他测试类**乃至开发库里**历史残留**的到期行，与正在执行的用例抢跑（症状：状态链凭空多出 `COMPENSATING → DEAD_LETTER` 节点、replay 的行被别人先耗尽、死信凭空少一条）。**全部 8 个 `@SpringBootTest` 测试类必须四个属性一起置 1h**（`*-fixed-delay-ms` + `*-initial-delay-ms` × 补偿/告警）；2026-09-18 补齐了 M1/M5/MonitorStats/ApicenterApplicationTests（此前只有 M2/M3/M4/StateChain 有）——**新增测试类照此模板，漏一个就会在全量套件下偶发红（单跑永远绿，命中率约 40%）**。细节见《技术踩坑记录.md》§11。
- **出站签名 prefix 可置空 = 裸 token（2026-09-18）**：`BearerTokenAuthAdapter` 的 `prefix` 为空时发送**裸 token**（不拼空格）——原实现无条件 `prefix + " " + token` 会产生前导空格 `" token"`，供应商要求裸 token/自定义头时会被判为无效凭证。回归：`BearerTokenAuthAdapterTest`（5 例：默认 / 置空无前导空格 / 自定义头名与前缀 / 凭证缺失 / 非出站阶段直通）。配套：FastMoss 联调若报业务码 `1002 invalid client_secret`，先按《使用教程》FAQ Q0.1 三步取证（凭证指纹 → 上游响应原文 → 直连二分）。
- **CORS 白名单不要写死单个 Origin（2026-09-18 真实缺陷）**：Spring **只跳过同源**请求的 CORS 校验，而 `127.0.0.1` 与 `localhost` 是**不同 Origin** ⇒ 白名单若只写 `http://localhost:5173`，用 `http://127.0.0.1:5173` 打开前端（或 Vite 端口被占自动 +1 到 5174）时，所有**写操作**（POST/PUT/DELETE 会带 Origin；GET 不带）被 CORS 层回 **403 `Invalid CORS request`** —— 请求**进不了引擎**、无 call_log/运行记录，极易误判为业务故障。现用 `allowedOriginPatterns` + 配置项 `app.api-center.cors.allowed-origin-patterns`（默认 `http://localhost:[*],http://127.0.0.1:[*]`；置空 = 不注册）。回归：`HttpErrorSemanticsTest#本机回环任意端口的Origin_不被CORS拒绝`。
- **XML 协议参数（`interface.protocol_params`）的四条纪律（2026-09-21 B1 落地）**：① **“键存在但为空白 → 40001；键缺失 → 内置默认”**——想用默认就**删掉那个键**（`namespace.prefix` 例外：空串 = 默认命名空间）；前端 `utils/protocolParams.mjs` 必须**只输出非空键**、全默认返回 `null`。② **声明与字节同源**：`XmlProtocolAdapter` 的 `createXMLStreamWriter(out, enc)` 才是编码权威，`writeStartDocument(enc, ver)` 的 encoding **仅在 writer 未指定时才生效**——将来做 encoding 时必须同源。③ **`encoding` 只是“改声明”**：Woodstox 对非 UTF-8 会把非 ASCII 写成 `&#x4e2d;`（字节保持 ASCII 安全）→ **产不出原生 GBK 字节**（已实测，属 backlog）；`UTF-16/UTF-32` **显式拒绝**（会产原生非 ASCII 字节、并让 call_log 失真）。④ **入站不解包**（Q17）：`soap` 配置只作用于【出站构造 + 响应解包】，DECODE 处“不读配置”是**有意的**（已在代码注释标明）。
- **`SnapshotChangeDiff` 的 `text()` 不能用 `asText()` 处理对象节点（2026-09-21 实现期发现的真 bug）**：Jackson 的 `asText()` 对 Object/Array 节点返回**空串** ⇒ 存入快照 `main` 的对象型字段（如 `protocolParams`）**变了却显示“无变化”**（变更摘要/change_note 静默失真）。现改为对象/数组走 `toString()`（标量键行为不变）。新增对象型 main 字段时必须过这条。
- **位置构造 + compat 重载：加字段不等于“零改动”（2026-09-21 教训）**：`InterfaceRow`/`InterfaceRequest` 靠 compat 构造器保证兼容，但**隐式 canonical 的 arity 一变，同参数量的调用会“改嫁”到 compat**（`long groupId`↔`Long groupId`、`BigDecimal version`↔`int version`）→ 报 `int→Long` / `BigDecimal→int`。**两条应对**：① 改动后用 `mvn -o test-compile`（**不加 clean**）核对；**结构变更**（新增 / 重命名 / 删除文件）会因旧 class 残留而**假通过** ⇒ **告知用户手动执行 `mvn -o clean test-compile`**；② 别用 `-q` 吞掉编译错误，也不要用 macOS 不存在的 `timeout` 包 mvn（会静默不执行）。

- **部分主机/WAF 按 `User-Agent` 拒绝 Java 客户端（2026-09-21 B2 验证期实测）**：`dneonline.com` 对 `User-Agent: Java/…` / `Java-http-client/…` **直接重置连接**（`SocketException: Connection reset`），而 `Apache-HttpClient` / `Mozilla` / curl 均 200。JVM 默认 UA 就是 `Java-http-client/…` ⇒ 平台默认会被这类主机拒，**表现为 `ResourceAccessException` → UNKNOWN/50401，极易误判为「超时」**。取证办法：`curl -A 'Java-http-client/21' …` 与默认 UA 对比。**待办（backlog）**：新增可配默认 `User-Agent`（或接口级静态头）。
- **传输异常必须打根因，不能只打异常类名（2026-09-21 修复）**：原先 `OutboundEngine` 只记 `e.getClass().getSimpleName()`，`ResourceAccessException` 下**连接超时 / 读超时 / 连接重置 / 协议错无法区分**——本次正是靠补上的 `rootCauseOf(e)`（`SocketException: Connection reset`）一步定位。新增传输分类日志时照此。
- **`SoapClientFaultException` 必须直接继承 `RuntimeException`（B2 关键契约，别改）**：绝不能继承 `HttpServerErrorException` —— `UpstreamInvoker` 的 `@Retryable(includes={HttpServerErrorException, TooManyRequests, ResourceAccessException})` 是**白名单收窄**（`includes` 默认空数组）⇒ 继承就会因 `instanceof` 命中而**继续重试**，且会被熔断计数分支误计失败（双重错）。同理三条：`OutboundEngine` 的 SOAP 分支必须放在 **instanceof 链最前面**（否则冒成 `50000`）；`PreStepExecutor` 必须**显式 catch**（否则归 `40001 CONFIG_ERROR`）；该异常在 `CallLogAspect` 归 **`upstream_fail`**（不是 transport_fail）。回归：`SoapClientFaultExceptionTest`（钉类型层次）。
- **`xml.type` 是协议参数的唯一真相（方案 A，2026-09-21 定稿）**：`soap` 段**不含 `version`**（版本由 type 携带，避免双真相）；**`type=POX` 带 `soap` 键 → `40001` 互斥**；`type` 缺省 = POX（B1 旧数据零迁移）。前端**只输出非空键**、非 XML 出站**恒不提交**；**`namespace.prefix` 从属于 `namespace.uri`** —— uri 为空时前缀**不产出**（后端也会以「缺 uri」40001），故界面在 uri 为空时**禁用前缀输入**+清空 uri **联动清空前缀**（教训：原先是静默丢弃，用户"填了、保存成功、值却不在"，见验收方案 §1.2 第 4 条）（`protocolParamsForPayload(protocolOut, …)`，**只看 `protocol_out`** —— 入站不解包，`in=XML/out=JSON` 时给入口就是「配了不生效」）。
- **Boot 4 已移除 `TestRestTemplate`（2026-09-18）**：集成测试要发真实 HTTP 用 `RestClient` + `@LocalServerPort`（并 `defaultStatusHandler` 禁抛以断言状态码），不要照旧记忆写 `TestRestTemplate`（编译期直接找不到类）。新增用例见 `HttpErrorSemanticsTest`。
- **`out_payload` 与 `call_log.req_body` 口径不同（2026-09-22 澄清）**：`outbound_request.out_payload` = **实际发送**的报文 ⇒ **GET/DELETE 恒为 null**（`OutboundEngine#outboundBodyText` 有意跳过，与 Invoker 行为对齐）；而 `call_log.req_body` = **编码产物**（GET 也有值）。**两个字段不一致不是缺陷**，界面已在状态机明细给出说明（`OutboundDetail.interfaceMethod` 供前端判断）。看"编出来的报文"用调用日志，看"实际发了什么"用状态机。
- **管理面「模拟回调」自调用读超时 = 20s 专用作用域（2026-09-22 修）**：网关侧**入站送达是同步的**（`InboundEngine.deliver` 复用 `UpstreamInvoker` 的 `@Retryable`），回调地址不可达时按 `max_retries` 内联短重试（退避 200/400/800/1600ms ⇒ 4 次 ≈ 3.0s）；而共享 `RestClient` 的兜底读超时是 `default-read-timeout-ms`（**2026-09-22 起 10000ms**，此前 3000） ⇒ 当时 **自调先超时**，管理面报 500「平台内部错误」，**但网关侧其实已处理完（ack 已回、送达另有状态与死信）**。现用 `PerRequestReadTimeoutFactory` 作用域包住自调（20s < 前端 `LONG_RUNNING_TIMEOUT` 30s），并把 `ResourceAccessException` 转成 **50401** 可读报错（提示按 traceId 核实）。回归：`M3IntegrationTest#模拟回调_回调地址不可达导致同步送达变慢_自调仍能拿到ack不报超时`（**反证已做**：作用域临时压到 1s ⇒ 该用例必红）。
- **入站鉴权闸门必须严守真值表：`mode=OFF` 判断放在最前（2026-09-23 全量套件抓到）**：§6.2 真值表第一行是「`OFF` + 任意主体 + 任意凭证 → **跳过鉴权**（不校验、不报错）」。把「带了凭证却没声明主体 → 40107」（那是 **`OPTIONAL`** 的行）放到 mode 判断**之前**，会把"调用方自带 `Authorization`/`X-Api-Key` 头、但平台尚未启用鉴权"的**既有调用误判为 401**（真实案例：`M4IntegrationTest.c6` 用 `Authorization` 头验证日志脱敏）。⇒ 新增判定分支时，先确认它属于真值表的**哪一行 / 哪个 mode**，并补一条该 mode 的回归用例。回归：`ClientAuthVerifierTest#OFF_无主体但带了凭证头_仍放行_真值表第一行`。
- **接入鉴权审计是异步批量写（2026-09-23 B4）**：`AccessAuthLogWriter` 与 `call_log` 同构（有界队列 + 50 条/1s 批量）。⇒ **测试断言审计行前要先等排空**（`flushNow()` 或轮询）——直接查库会偶发"查不到"。另：`access_auth_log` **加列要同步三处**（`insertBatch` 的列清单与位置绑定序号、视图 record、`toLogEntry` 映射），位置绑定错位会**静默写错列**（与 `call_log` 同一坑，2026-09-18 已记）。
- **入站鉴权闸门的位置与「新增出站路径」义务（2026-09-23 B3）**：闸门只在 `GatewayController` 里、**路由命中后 / 引擎之前**执行，且**只对 `if_type=OUTBOUND`** 生效（`INBOUND` 走链内回调验签，D-CA-3）。⇒ **绕过网关的出站调用不受闸门约束**：前置编排（`PreStepExecutor` 直调 `ChainEngine`）、补偿/重送 worker、管理面 `/test`（直调引擎）都**不经闸门**——这是设计如此，不是漏洞；但**新增「从外部进平台」的入口路径时必须同样接上闸门**（否则漏一道）。另：`client-auth.mode=OFF`（默认）时闸门内部早退（零校验），但**仍写审计**（观察期口径）；`ENFORCED` 下**平台自调必须带 `X-Internal-Token`**（见 D-CA-17）。
- **调试工具输入框：格式随「入站协议」+ 无损美化 + 自带样式（2026-09-22）**：`components/RequestBodyEditor.vue`（测试接口 / 模拟回调共用）
  按 `protocol_in` 显示格式标签与提示（含"填错会看到的报错码"），「美化结构」走 `utils/requestBody.mjs#beautifyBody`
  → 复用 `payload.mjs` 的**无损**缩进（失败**不吞输入**，只提示原因）；预填按协议给骨架（XML `<request></request>` / JSON `{}`）
  并自动美化；内容与协议的**格式不符会实时预告**（`bodyMismatchHint`，2026-09-22——避免靠试错发现 40002）。**顺手修的既有 bug**：两个弹窗原用 `class="raw-editor"`，但该样式定义在 `InterfaceParamsTab.vue` 的
  `<style scoped>` 里 ⇒ **实际不生效**（浏览器默认 textarea）；现由该组件自带深色等宽样式。
- **调试工具的报文格式随「入站协议」（2026-09-22 修）**：`POST /interfaces/{id}/test` 与 `/test-callback` 后端都收 **`byte[]` 原样字节**，再按 `protocol_in` 解码 ⇒ **前端必须按入站协议准备报文**（`utils/testPayload.mjs#buildTestPayload`：XML 入站原样发 `application/xml`；JSON 入站解析成对象）。原实现两处都**无条件 `JSON.parse`** ⇒ **入站 XML 的接口填 XML 必报** `Unexpected token '<', "<request><"... is not valid JSON`（且报文预填在 XML 接口下恰好是 XML）——**踩坑时先查入站协议，不要怀疑引擎**。另：接口弹窗的「**同入站**」开关**默认开**，此时「出站协议」下拉是**禁用**的（跟随入站）⇒ 要配「入站 JSON / 出站 XML」必须**先关掉它**；**验收方案已统一为「入站 XML / 出站 XML」以免踩此坑**（2026-09-22 用户按旧版方案配出 XML/XML，却按新版 §2.1 填了 JSON ⇒ 前后两轮都踩到）。填错格式两个方向**都有明确报错**：入站 XML 填 JSON → 后端 `40002 报文格式非法：… expected '<'`；入站 JSON 填 XML → 前端可读提示。
- **`call_log` 加列要同步三处（2026-09-18）**：`insertBatch` 的列清单与**位置绑定序号**、`LIST_COLUMNS`（列表投影）、`CallLogView` + `toView`——位置绑定错位会**静默写错列**，不报错。`step_code` 即此模式（前置调用的 OUT 条带步骤名，Monitor 可按步骤筛选）。
- **保存期校验口径（2026-09-18 补）**：`interface.path` 必须 `/` 开头；`interface.upstream_path` 拒绝对 URL、`..` 与空白/`<>\"{}|\\^` 等 URI 非法字符（否则运行期 `URI.create` 抛 `IllegalArgumentException` → 500）；`app.base_url` 与回调地址共用 `CallbackUrlValidator`（格式 + `callback-allow-private` 开关下的内网拦截）。**新增目标地址类字段时照此接上校验**。
- **「死信编号」语义（2026-09-18 修正）**：`insertDeadLetter` 现回填**真实 `dead_letter.id`**，响应 msg 里的编号可直接用于 `POST /monitor/dead-letters/{id}/replay`（此前是 `outbound_request.id`，会打错记录）。
- **`deleteByApp` 必须带 `biz_type` 过滤（2026-09-18）**：`dead_letter.ref_id` 是多态引用（OUTBOUND→`outbound_request.id`，INBOUND→`inbound_delivery.id`），两表自增 id 空间重叠，裸 `ref_id IN (…)` 删会误删另一方向的死信。两处 `deleteByApp` 已各加 `AND biz_type='OUTBOUND'|'INBOUND'`。
- **调用日志列表已瘦身（2026-09-12）**：`/monitor/call-logs` 不含 `req_headers`/`req_body`/`resp_body`，详情走 `GET /monitor/call-logs/{id}`（前端抽屉打开时按 id 拉）；`keyword` 走 `url LIKE`，服务端强制时间窗 ≤7 天（未传按近 24h）。
- **侧边栏 / 顶部栏视觉口径（2026-09-12）**：`layout/MainLayout.vue` 全量对齐 `API中心原型.html`——侧边栏 200px、底色 `#1d2129`、logo = 主色圆点 `#2f54eb` + 「API 中心」（原型**无 SVG 图形 logo**）、菜单几何字形 `◧▤▦⇄◎⚙`（勿用 emoji）、菜单项 hover `rgba(255,255,255,.06)` / active 主色底、底部脚注；顶部栏 56px 白底 + `管理面 / 页面标题`；内容区 padding 24px；全局背景 `#f5f6f8`。菜单顺序按原型（接口监控在适配器之前）。
- **列表列口径与应用数字 ID（2026-09-12）**：`app` 表新增 `id BIGINT AUTO_INCREMENT UNIQUE`（DDL 已应用到开发库；`schema.sql` 有迁移块），应用列表两列并存：「ID」= 数字 id、「应用标识」= `app_id`；凭证角标列已移除。**对外契约一律仍用 `app_id`**（URL `/apps/{appId}`、凭证归属、分组/接口引用、监控过滤、groupCount 子查询），`id` 仅作列表展示/运维引用——新增代码不要拿 `id` 当业务键。接口列表新增「ID」列（数字主键，普通表头不带 tooltip），「应用」列保持只显示应用名称。
- **内存态清理钩子（2026-09-12）**：删接口/删应用/删告警规则时要清 `CircuitBreakerRegistry.evict` / `GatewayGuard.evict` / `AlertService.evictApp|evictRule`，新增「删除」入口请照此补。
- **报文展示禁止 parse 重建（v0.3）**：调用日志 / 状态机 / Dashboard 的报文美化走 `utils/payload.mjs` 的**扫描式缩进**（只增删空白）——**不要**改成 `JSON.parse` + `stringify`（19 位数字尾数会被改写、`1.10`→`1.1`、重复键丢失）或 XML `DOMParser`（规范化 CDATA / 实体 / 属性引号）；新增展示点复用 `components/PayloadViewer.vue`，展示取值统一走 `pickPayloadText(analyzed, mode)`；高亮 tokenizer 需保持**无损**（token 拼接 = 输入，单测有断言），Worker 与 localStorage 必须做能力守卫（SSR 冒烟会跑）。**`<script setup>` 里的 computed/watcher 在 JS 中必须 `.value`**（只有模板自动解包）——曾因此让抽屉正文恒显占位符「—」，现由 `npm test`（单测 + 组件 SSR 冒烟）拦住；改格式化逻辑或组件绑定后必须跑 `cd frontend && npm test`。
- **更多 Spring 7 / Jackson 3 / WireMock 3 踩坑**：见 `doc/开发文档/技术踩坑记录.md`（写代码前先查）。
- **术语口径（2026-09-08 定稿）**：用户可见文案与文档用「**供应商**」表被代理的角色（供应商 5xx/拒绝/超时/返回、依赖供应商幂等）、「**供应商接口路径**」表出站路径；`upstreamPath` / `upstream_path` / `UpstreamInvoker` 为稳定契约与内部标识**不改名**；链路方向叙述（调供应商）与代码内部注释可保留「上游」。勿引入「第三方」作主术语（与平台客户歧义）。
- 旧 demo 实现仅供参考（git 历史 `ed95446` 及之前），不照搬渠道特化逻辑（PARTNER_A/B、订单字段、高水位同步均不适用于新设计）。

## 文档导航

- **现行设计**（`src/main/resources/doc/`）：`API中心设计方案.md`（总纲）→ `技术架构和实现方案.md` / `可行性报告.md` / `表结构设计.html` + `schema.sql`（实现四件套）→ `API中心时序图与流程图.md` / `API中心原型.html`（流程与交互）→ `开发计划.md`（M0–M5 里程碑 + fastmoss 黄金用例，执行入口）
- **M0 契约**（`src/main/resources/doc/开发文档/`）：链引擎 / 映射语义 / 客户端对账三份 + 凭证轮换存储方案 M0-04（全部已评审通过，M1/M2 编码依据）
- **里程碑计划**（`src/main/resources/doc/开发文档/`）：`M3开发计划.md` / `M4开发计划.md`（均已实施）/ `M5开发计划.md`（已评审定稿，下一步）/ **`B2完整SOAP开发计划.md`（延后待真实样本）** / 各里程碑手动验收方案 + **`协议参数手动验收测试方案.md`**（B1+B2 协议参数） + **`入站送达异步化评估.md`**（v1.0 评估稿：ack 时延与同步送达的耦合、A/B/C/D 对比与推荐） + `M5压测报告.md`（M5.3 压测方案与报告，待执行回填）+ 代码评审记录
- **SOAP 真实样本**（`src/test/resources/soap-samples/`，2026-09-21）：6 个公网免密钥 SOAP 服务的 **WSDL + 成功/Fault 原始报文**（37 文件）；含 B2 的 G1 材料与 4 条设计修正 C-1~C-4（`action` 可空 / Fault 穷举 `VersionMismatch`·`MustUnderstand` / 业务错误走 200 / 解包按 localName）；可作 WireMock 夹具
- **踩坑记录**（`src/main/resources/doc/开发文档/技术踩坑记录.md`）：Spring 7 / Jackson 3 / WireMock 3 API 差异与经验（写代码前先查）
- `README.md` — 项目索引
