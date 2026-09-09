# API 中心 · 重新设计方案

> 5 个功能模块即顶层：应用管理、分组管理、接口管理、接口监控、适配器。定位为基础 API 中转 + 字段映射，支持 JSON 与 XML 协议。适配器整合鉴权、协议、报文三类，字段映射为接口级配置，按适配器链编排。可靠性、可观测等横切能力归入相关模块；第 6 章为状态机 / 错误码 / 容错机制附录。

## 1. 应用管理

### 1.1 应用模型与生命周期
- 应用 = 平台对接的一方供应商（第三方上游，如腾讯云 / 阿里云 / 智慧芽），持有 appId/appSecret；平台代理调用该供应商的接口。
- 供应商即上游：接口归属某供应商，也转发到该供应商，二者合一，不再区分「归属应用」与「上游应用」。
- 核心字段：appId（全局唯一）、应用名称、联系人、创建/更新时间。
- IP 白名单 / 黑名单：来源 IP 控制（多个 IP 用英文逗号分隔，为空时不限制请求 IP）。
- 服务地址（base-url）：平台调用该供应商时的目标地址（供应商 API 根地址，如 https://cvm.tencentcloudapi.com）。
- OAuth2 的 token URL / 授权回调地址属鉴权适配器配置（见 5.8 适配器配置元数据），不在应用级字段内。
- 生命周期状态机：草稿 → 启用 → 停用 → 注销；停用即拒绝其请求，注销后回收 appId。

### 1.2 应用凭证（出站签名 / 回调验签两类）
- 每应用持有一对 appId + appSecret，并按鉴权方向拆成两类凭证：
  - 出站凭证（平台作为调用方签名用）：appSecret / 云厂商 secretId+secretKey / OAuth2 clientSecret / Bearer token 等。
  - 回调验签凭证（平台验证供应商回调签名用，仅入站回调接口）：回调 HMAC secret / 云厂商回调 token 等，独立于出站凭证。
- 两类凭证均加密存储（可逆加密或 KMS/HSM），不落明文；不得单向哈希——签名/验签都需用明文密钥重算。
- 两类凭证均支持轮换（新旧短暂并存，平滑切换）、重置与即时失效。

### 1.3 应用接入流程
- 创建应用（填资料）→ 签发密钥 → 启用。
- 支持自助创建、无需审批，全程留痕（谁创建、何时、改了什么）。
- 分组与接口分别在「分组管理」「接口管理」中配置。

### 1.4 应用级配置（限流 / 配额 / 黑白名单）
- 每应用配额：QPS 限流、日调用量上限。
- 黑白名单：来源 IP / 调用范围控制。
- 超限处理：限流拒绝 + 告警，不污染业务状态机。
- 应用级默认 = 出站鉴权 + 回调验签 + 默认报文适配器；接口可覆盖（详见 5.7）。

## 2. 分组管理

### 2.1 分组模型
- 分组是应用下的组织单元，纯归类/展示用，不承载配置。
- 层级：应用 → 分组 → 接口；接口归属唯一应用，经分组归入。
- 分组字段：分组标识、名称、所属应用、排序。

### 2.2 分组管理功能
- 跨应用查看/管理所有分组（按应用组织的两级视图）。
- 支持分组增删改查、接口在分组间移动。
- 分组下可查看其接口列表，点击进入接口详情。

### 2.3 接口归属（两级下拉）
- 新建接口时「归属」为两级下拉：先选应用，再选该应用下的分组。
- 接口创建后即归属该应用的分组（父子关系）。

## 3. 接口管理

### 3.1 接口定义模型（类型 / 方法 / 协议 / 应用 / 鉴权）
- 接口 = 平台对外暴露/代理的一个 API 定义。
- 核心字段：接口标识、接口类型（出站中转 / 入站回调）、HTTP 方法、平台侧路径（如 /api/orders、/callback/{appId}/instance-state）、应用（供应商，appId，既是归属也是上游）+ 目标地址（出站 = 上游路径 path；入站 = 回调地址 deliveryUrl）、描述。
- 归属：接口属于某应用下的某分组（应用 → 分组 → 接口），新建时经两级下拉选择。
- 鉴权：出站中转接口只配「供应商签名」（出站鉴权，应用级默认 + 接口级覆盖）；入站回调接口只配「回调验签」（入站鉴权，应用级默认 + 接口级覆盖）；调用方鉴权 / 向回调地址签名由平台统一处理，不在接口模型内。
- 协议（入站 / 出站各一，JSON/XML）：入站协议 = 来源→平台的报文格式，出站协议 = 平台→目标的报文格式，二者可不同；组合即 json-json / json-xml / xml-xml / xml-json 四种场景，协议适配器按协议自动推导，默认「出入站一致」。
- 请求参数：分「入站侧（来源→平台）」与「出站侧（平台→目标）」两侧；每侧 Params（参数名 / 类型 / 必填 / 示例值）与 Body（none / form-data / x-www-form-urlencoded / json / xml）两个 tab（入站回调的「出站侧」= 送达报文，必填）。
- 字段映射（入站 → 出站）：每条 = 入站字段(source) + 操作 + 出站字段(target) + 空值策略，source/target 从两侧参数下拉选择。
- 响应 / ack：出站 = 出站响应字段（RESP 白名单——仅声明字段随 data 返回并按 type 解析；声明为空不过滤）；入站 = ack 回执（接口声明 ACK 字段按 sort_order 取前 2 个，字段名可配置、值固定：第 1 = 回执码 0、第 2 = 消息 success；无声明兑底平台统一信封 `{code:0, msg:"ok", data:null}`；渲染格式随入站协议 JSON/XML，XML 根元素取约定名 response）；ack 收到即回、与送达结果解耦，不回传调用方 ack。平台侧对外响应为统一信封 `{code, msg, data}`，不逐接口配置。

### 3.2 接口归属与适配器链
- 接口归属唯一应用（经分组），父子关系，不再多对多授权。
- 接口可绑定鉴权与报文适配器，未绑定时继承应用默认；协议适配器按接口入站 / 出站协议自动推导；字段映射为接口级配置（见 3.1 / 5.6）。

### 3.3 接口级配置（超时 / 重试）
- 读超时（默认 3000ms）：出站 = 调上游超时；入站 = 回调地址调用超时。
- 重试策略：最大重试次数、退避、重试条件（5xx/429）。

### 3.4 接口生命周期（草稿 / 发布 / 下线 / 版本）
- 草稿 → 发布 → 下线。
- 版本化（M5 已落地）：接口配置变更自动生成新版本快照（含变更说明 change_note），支持版本历史查询、快照回滚与适配器灰度；发布 / 下线等生命周期流转不生成版本；回滚以历史快照全量重建接口配置——版本号只增、生命周期状态不受影响；适配器灰度经接口绑定指定 version 路由（见 5.7）。
- 下线后停止路由。

### 3.5 接口调用链路（出站 Flow A / 入站 Flow B）
- Flow A 出站：调用方 → 平台接口 → 适配器链 → 调供应商 → 反向适配 → 回调用方。
- Flow B 入站：供应商回调 → 平台接口 → 适配器链 → 送达调用方 → ack。
- 状态机载体：出站为出站请求记录状态，入站为送达记录状态。

### 3.6 接口级容错（熔断 / 重试 / 补偿 / 死信 / 对账）
- 熔断：上游持续失败时快速失败，跳过重试直接进补偿 / 死信（详见 6.4）。
- 5xx/429 短重试 → 耗尽补偿。
- 4xx 死信。
- 超时 → UNKNOWN 对账。
- 补偿 worker 定时扫描。

## 4. 接口监控

### 4.1 调用日志（请求 / 响应 / traceId / 脱敏）
- AOP 拦截记录每次调用的请求/响应/耗时/结果。
- traceId 贯穿全链路。
- 敏感字段脱敏（手机号、密钥、Header 敏感值）。

### 4.2 成功率与延迟指标
- Micrometer/Prometheus 指标：调用量、成功率、P50/P95/P99 延迟。
- 按接口、应用（供应商）维度聚合。

### 4.3 链路追踪
- OpenTelemetry 集成，span 贯穿平台→上游。
- 可定位到具体节点耗时与失败。

### 4.4 告警策略
- 阈值告警：成功率下降、延迟超限、死信堆积、补偿失败。
- 通知渠道（邮件 / IM）。

### 4.5 失败诊断与对账查询
- UNKNOWN 状态对账查询。
- 失败请求快速定位（按 traceId / orderId）。
- 死信查看与重放。

### 4.6 状态链（状态流转历史）

出站请求 `outbound_request.status` 只存当前值、被原地覆盖，无法回溯「这条请求一路怎么走过来的」。状态链以**事件溯源**方式追加记录每次状态真正变化，按时间顺序还原完整流转过程，供监控页可视化与故障定位。

- **数据模型（第 19 张表 `outbound_request_state_log`，append-only）**：每行 = 一次状态转移 `from_status → to_status` + 触发来源 `trigger` + 补充说明 `detail` + 变更时 `attempt / error_code / trace_id`。当前状态仍由 `outbound_request.status` 承载（worker 扫描 / 统计 / 告警全部不动），状态链只是其历史镜像。
- **埋点口径（折中方案）**：只在 `status` **真正变化**时记录（`from == to` 的顺延——如熔断期 `COMPENSATING → COMPENSATING` 只改 next_retry_at——不产生节点）；`trigger` 枚举区分同一 `from→to` 的多成因（如 `UNKNOWN→COMPENSATING` 有人工/TTL 两种）：`FIRST_SEND / COMPENSATE / CIRCUIT_OPEN / RECONCILE_MANUAL / TTL_DOWNGRADE / REPLAY / EXHAUSTED`。
  - **实现分层（WAN 性能决策）**：主请求路径（首送 / 补偿重放，高频）的节点经请求内攒批，在 `execute`/`replay` 出口 `flushStateChain` **一次批量 INSERT**（每请求 1 次批量 + 1 次 max(seq) 查询）；低频运维路径（人工对账 / TTL / 死信重放 / 补偿耗尽）即时 `transition`（逐节点同步写，低频无感）。背景：逐节点「SELECT from + UPDATE + INSERT」在远程库（~百 ms/往返）下每请求增多次往返，会拖慢主链路并破坏熔断窗口等时序敏感测试（实测 M4 熔断用例因此失败，批量后恢复）。
- **状态列与链解耦**：状态列 `outbound_request.status` 由各路径即时 `updateState`（worker 扫描 / 熔断 / 对账依赖实时状态，不动）；状态链只是其历史镜像，允许入口 flush 失败丢链（只影响可观测，不影响主链路结果，见坑 3）。
- **SENDING / RETRYING 不产生节点（折中方案取舍）**：短重试发生在 `@Retryable`（UpstreamInvoker）内部、不写库。状态链只呈现**真实落库的转移** `INIT → MAPPING → 终态`，短重试次数（`RETRY_FAILURES`）并入终态节点的 `detail`（如「5xx 重试耗尽（短重试 4 次）」）。这是与 §6.1 声明状态机（含 SENDING/RETRYING）的**有意差异**：运维价值等价（能回答「重试了几次才耗尽」），且不触碰 @Retryable 内核。
- **查询**：并入 `GET /api/admin/monitor/outbound-requests/{id}` 的 `OutboundDetail.stateChain`（`[{seq, fromStatus, toStatus, attempt, errorCode, trigger, detail, createdAt}]`），详情抽屉一次拉取。
- **前端入口与展示**：
  - **入口**：Monitor「状态机与对账」tab 操作列按钮「详情/审计」→ 更名「状态链/详情」，打开 600px 详情抽屉，状态链置顶（基本信息之后、报文之前）；表格「状态」列 tag 可点击直达（P1）；route query 深链 `?tab=queue&id={outboundRequestId}` 从告警/仪表盘跳转（P2）。
  - **组件**：`el-timeline`（纵向时间线，动态节点数 + 历史回溯语义，优于 el-steps 固定步骤条）；节点 = 状态着色圆点 + 中文状态名 + 时间 + attempt/max，副行 = trigger 文案 + detail（含错误码）；最后一个节点即当前状态，加粗描边 + 「当前」角标高亮。
  - **状态中文/颜色**（补 `MAPPING`，现有 STATUS_LABEL 缺）：INIT=初始(info) / MAPPING=映射中(info) / COMPENSATING=待补偿(warning) / SUCCESS=成功(success) / DEAD_LETTER=死信(danger) / UNKNOWN=对账中(warning)。
  - **trigger 文案**：FIRST_SEND=首送 / COMPENSATE=补偿重放 / CIRCUIT_OPEN=熔断短路 / RECONCILE_MANUAL=人工对账 / TTL_DOWNGRADE=TTL 降级 / REPLAY=死信重放 / EXHAUSTED=重试耗尽。
  - **空态**：无 state_log（历史数据）时显示「暂无状态链（该记录创建于状态链上线前）」。
  - **与对账审计时间线互补**：状态链（state_log）展示全生命周期流转，对账审计（reconcile_audit）展示 UNKNOWN 对账的 operator/reason 详细留痕，两者数据源不同、都保留不重复。

**坑 / 边界（实现必读）**：
1. **短重试次数读取时序**：`RETRY_FAILURES` 在 `UpstreamInvoker.endRetryBudget()`（doInvoke 的 finally）里被 remove，而传输异常分类 `classifyInvokeFailure` 在 finally **之后**的 execute catch 里才执行——直接读会拿到空。折中方案必须把传输异常分类**下沉到 `doInvoke` 的 catch 内**（重试次数在同一作用域可读），或 `endRetryBudget()` 改为返回失败次数；否则 detail 里的「短重试 N 次」恒为 0。
2. **历史数据无链**：表上线前已有的出站记录无 state_log，状态链只能看到当前状态一个节点（运行表短生命周期，可接受）。
3. **高频追加**：state_log 与 call_log 同属 append-only 高频表，按保留期归档/清理；写入为独立短 INSERT，失败只记日志、不影响主链路（不污染状态机）。
4. **seq 排序**：同一请求首送与补偿重放串行（worker 单线程 + attempt 串行），seq 冲突概率极低，兜底按 created_at + 自增 id 排序。
5. **范围**：本期只做**出站**状态链；入站 `inbound_delivery` 送达状态机（RECEIVED/ACKED/PENDING/DEAD_LETTER）链路更简单，列为后续扩展。
6. **`trigger` 为 MySQL 保留字**：`outbound_request_state_log` 的物理列名用 `trigger_src`（schema.sql / 表结构设计.html / repository SQL 一致），逻辑与 API/前端字段名仍叫 `trigger`（Java record、OutboundDetail.stateChain 元素字段、前端 TRIGGER_LABEL 均用 trigger），仅 DDL/SQL 层规避保留字。

## 5. 适配器

### 5.1 适配器体系总览
- 三类适配器：鉴权、协议、报文；字段映射为接口级配置（见 5.6），作为链上固定步骤而非全局适配器。
- 统一适配器接口，可插拔、可扩展。
- 适配器链：请求按固定顺序流过「入站鉴权（验来源：调用方 / 供应商回调）→ 协议解码 → 报文适配 → 字段映射 → 协议编码 → 出站鉴权（向供应商 / 回调地址附加凭证）」；其中调用方鉴权与向回调地址签名由平台统一处理，不在接口模型内。
- 以「统一内部模型」为链内唯一数据载体（格式无关）。
- 四种端到端转换场景（由整条链协作完成）：json-json / json-xml / xml-xml / xml-json（入站协议与出站协议各一、可不同，如 json-xml）。
- 鉴权 / 报文适配器绑定到接口 / 应用，未绑定用平台默认；协议适配器按接口协议自动推导，不参与绑定。

### 5.2 基适配器设计
- 顶层基适配器（Adapter）：所有适配器的统一契约（接口），三类适配器均实现它。核心方法：
  - `AdapterType type()`：标识三类（鉴权 / 协议 / 报文）。
  - `int order()`：链内执行顺序。
  - `boolean supports(AdapterContext ctx)`：是否适用于当前请求。
  - `AdapterContext process(AdapterContext ctx)`：执行并返回（可能已变更的）上下文。
  - 适配器无状态、配置驱动，链上复用。
- 链上下文（AdapterContext）：适配器链中传递的唯一上下文，携带：
  - 统一内部模型（payload，格式无关）。
  - 元数据：接口标识、应用标识（供应商）、输入/输出协议类型、traceId。
  - 鉴权结果（appId、是否通过）。
  - 错误 / 告警收集（各适配器可追加）。
  - 作用：解耦上下游，新增适配器只读写上下文。
- 鉴权基适配器（AuthAdapter）：契约含两个方向——入站 authenticate（验证来源：调用方或供应商回调，输出通过 / 拒绝 + appId）与出站 applyCredential（向对端附加凭证）；编码落地（M0-01 定稿）以统一契约 `Adapter.process(ctx)` + `ctx.phase()` 分流实现（INBOUND_AUTH 执行验签、OUTBOUND_AUTH 执行签名），不做同一实例双方向回调。首期已落地实现：NoopAuthAdapter、ApiKeyAuthAdapter、HmacAuthAdapter、HmacCallbackVerifyAdapter（回调验签，M3）、BearerTokenAuthAdapter、CloudSignatureAdapter、CloudCallbackSignatureAdapter；OAuth2 Client Credentials / OAuth2 授权码 / Basic Auth / mTLS 为规划实现（未排期，5.8 参数结构预留）。
- 协议基适配器（ProtocolAdapter）：契约（双向）`UnifiedModel decode(bytes, format)` / `bytes encode(UnifiedModel, format)`，是「格式」的唯一责任方。具体实现：JsonProtocolAdapter、XmlProtocolAdapter。
- 报文基适配器（MessageAdapter）：契约 `UnifiedModel adapt(UnifiedModel)`，做报文结构转换（信封/包裹、报文头、请求/响应结构）。具体实现按应用定制（EnvelopeMessageAdapter、HeaderMappingAdapter 等）。
- 字段映射（接口级配置，链上固定步骤）：做字段级转换，格式无关，规则在接口级配置（见 5.6 / 3.1），不作为全局适配器实例；json-json / json-xml / xml-xml / xml-json 四种组合由「协议适配器 + 字段映射」协作完成。

### 5.3 鉴权适配器
- 可插拔鉴权策略（按需选用，每个策略均含「入站验证 authenticate」与「出站签名 applyCredential」两个方向，按接口/应用分别绑定）：
  - API Key（静态密钥：Header 名 + API Key 值）
  - HMAC 签名（简单 HMAC：签名算法 / 签名头 / 时间戳容差 / 防重放）
  - 云厂商签名（腾讯云 TC3 / AWS SigV4 / 阿里云 ACS3：SecretId / SecretKey / 服务名 / 地域 / 签名头）
  - 云厂商回调验签（腾讯云事件通知 / AWS SNS / 阿里云回调签名：回调 token / 证书验签）——入站方向专用
  - OAuth 2.0 Client Credentials（机器对机器：Token 端点 / Client ID / Client Secret / Scope）
  - OAuth 2.0 授权码（授权地址 / Token 端点 / Client ID / Client Secret / 回调地址 / Scope）
  - Bearer Token（静态 token：Token / Header 名 / 前缀）
  - Basic Auth（HTTP 基础认证，仅限 HTTPS）
  - mTLS（双向证书：客户端证书 / 私钥 / CA 证书 / 校验方式）
  - 无鉴权（内网 / 演示）
- 回调验签（供应商 → 平台，仅入站回调接口）：
  - 用「回调验签凭证」验签（HMAC 回调 / 云厂商回调验签），独立于出站签名凭证。
  - 失败返回 401；连续失败告警 / 临时封禁（防暴力破解）。
  - 调用方鉴权（平台自己的客户，如 ERP）由平台统一处理，不在本模型内。
- 出站鉴权（平台作为调用方，向目标证明身份）：
  - 出站中转：按供应商要求附加凭证（API Key header / 云厂商签名 / Bearer Token / OAuth2 client_credentials / mTLS 客户端证书）。
  - 入站回调：向回调地址附加凭证（可选，默认无）。
- 密钥管理：出站 / 入站两类凭证均加密存储、轮换（新旧并存）、泄漏即时失效。
- 绑定关系：出站鉴权与回调验签各为「应用级默认 + 接口级覆盖」两个独立绑定；回调验签仅对入站回调接口生效。

### 5.4 协议适配器（JSON / XML 编解码）
- 接口声明协议（JSON/XML）→ 协议适配器实现对应编解码。
- 协议适配器为**无状态全局单例**，编解码参数（命名策略 / 日期格式 / 根元素等）经适配器配置实例逐调用注入 ctx 生效——实现共享编解码器并按请求参数覆盖，不做每应用独立 ObjectMapper / XmlMapper（M0-01 D5 口径，实例隔离由配置参数保证）。
- JSON 编解码：命名策略、日期格式、忽略未知字段、空值策略、数字精度。
- XML 编解码：根元素、命名空间、属性 vs 元素映射。
- 解析失败容错：明确错误码 + 落日志，不污染状态机。

### 5.5 报文适配器（输入 / 输出报文转换）
- 管「外壳/骨架」：输入报文 → 统一内部模型；统一内部模型 → 输出报文。
- 报文结构转换：信封/包裹、报文头处理。
- 响应信封映射：剥上游信封（`envelope`，如 `data`）+ 读上游状态码（`codeField`/`successValue`）判断成败 + 错误码映射（`codeMappings`）+ 包平台统一信封 `{code, msg, data}`（`msg` 透传上游 `messageField`）。
- 入站回调 ack：平台回供应商的「回执」结构可配置（ack 字段列表，固定 code/message）；收到回调即回，与送达结果解耦（送达失败仍回 ack，供应商不重发）。
- 分工边界：报文适配器管报文整体结构与状态码，字段映射（接口级）管字段内容。

### 5.6 字段映射（接口级配置）
- 字段映射在「接口级」配置，不再作为全局适配器：每条规则 = 入站字段(source) + 操作 + 出站字段(target) + 操作参数(param，可选) + 空值策略，source/target 从接口的两侧请求参数下拉选择；枚举映射 / 默认值 / 条件 / 聚合 / 类型转换等参数化操作需填 param。
- 方向明确为「入站 → 出站」；响应方向的反向映射暂不建模（入站回调的 ack 是「回执」而非响应回显，故无需反向映射，送达结果只落内部状态）。
- 扩展方向：响应方向字段映射（供应商字段 → 平台字段）已列入《开发计划.md》§1.1「扩展候选」（未排期），需求明确后另行设计评审。
- 操作：重命名（rename）、类型转换（typeCast）、枚举映射（enumMap）、默认值（default）、条件（condition）、聚合（aggregate）。
- 字段级转换：重命名、类型转换、枚举映射、默认值 / 常量注入、条件与空值策略。

### 5.7 适配器链编排与绑定
- 链顺序：入站鉴权 → 协议解码 → 报文适配 → 字段映射 → 协议编码 → 出站鉴权。
- 鉴权 / 报文适配器绑定到接口 / 应用，可插拔、可覆盖，未绑定继承默认；协议适配器按协议自动推导。
- 元数据驱动，新增适配器不影响既有链路。
- 适配器可配置化、版本化（D6' 定稿，2026-09-08）：多实例并行首选同 impl 不同 version；binding.version 仅记录/留痕，**不再参与运行时路由**——绑定即实例（恒用绑定行 adapter_id 所指实例）；同 (impl, version) 允许多条启用（实例靠 id + adapter.name 全表唯一区分）；目标实例缺失 / 停用 → 逐层回退应用默认 → 平台默认 Noop，并告警留痕。

### 5.8 适配器配置元数据（字段结构）

适配器「无状态、配置驱动」——配置元数据即适配器运行时读取的全部参数。每类适配器配置均含统一外层字段 + 各自 `params`：

- 统一外层：`adapterType`（auth / protocol / message）、`impl`（具体实现类）、`enabled`（启用 / 停用）、`version`（版本，用于版本化 / 灰度）、`params`（该类适配器的具体参数，见下表）。

| 适配器 | params 关键字段 | 说明 |
|---|---|---|
| 鉴权 · API Key | `apiKey`、`headerName` | API Key 值（遮显）、携带密钥的 Header 名（X-API-Key / X-App-Id / X-Auth-Token / api-key） |
| 鉴权 · HMAC | `signatureAlgorithm`、`signatureHeader`、`timestampToleranceSeconds`、`replayProtection` | 签名算法（HMAC-SHA256/SHA1/SHA512）、签名头名、时间戳容差（300s）、是否防重放 |
| 鉴权 · 云厂商签名 | `scheme`、`secretId`、`secretKey`、`service`、`region`、`signedHeaders` | 签名规范（TC3-HMAC-SHA256 / AWS4-HMAC-SHA256 / ACS3-HMAC-SHA256）、SecretId、SecretKey、服务名、地域、签名头 |
| 鉴权 · 云厂商回调验签 | `scheme`、`token`、`certificate` | 回调验签规范（腾讯云事件通知 / AWS SNS / 阿里云回调）、回调 token、验签证书（AWS SNS X509） |
| 鉴权 · OAuth2 Client Credentials | `tokenUrl`、`clientId`、`clientSecret`、`scope` | token 端点、Client ID、Client Secret、Scope |
| 鉴权 · OAuth2 授权码 | `authorizationUrl`、`tokenUrl`、`clientId`、`clientSecret`、`redirectUri`、`scope` | 授权地址、token 端点、Client ID、Client Secret、回调地址、Scope |
| 鉴权 · Bearer Token | `token`、`headerName`、`prefix` | Token（遮显）、Header 名（Authorization / X-Auth-Token / X-Access-Token）、前缀（Bearer / Token） |
| 鉴权 · Basic | `username`、`password` | 基础认证（仅限 HTTPS） |
| 鉴权 · mTLS | `clientCert`、`clientKey`、`caCert`、`verifyMode` | 客户端证书 / 私钥 / CA 证书（文件上传）、校验方式（STRICT / OPTIONAL / NONE） |
| 协议（protocol） | `format` | JSON / XML；协议适配器按接口入站 / 出站协议自动推导 |
| 协议 · JSON | `namingStrategy`、`dateFormat`、`ignoreUnknown`、`nullHandling`、`numberPrecision` | 命名策略、日期格式、忽略未知字段、空值策略、数字精度 |
| 协议 · XML | `rootElement`、`namespace`、`attrVsElement` | 根元素、命名空间、属性 vs 元素映射 |
| 报文 · 信封（EnvelopeMessageAdapter） | `envelope`、`codeField`、`successValue`、`codeMappings[]`、`messageField`、`defaultErrorCode` | 业务数据容器（data）、上游状态码字段、成功值、错误码映射、消息字段、兜底错误码 |
| 报文 · 报文头（HeaderMappingAdapter） | `headerMappings[]` | 报文头字段映射规则 |
| （字段映射为接口级配置） | 见 3.1 | 字段映射规则不再作为适配器参数，改为接口级 `fieldMappings` |

字段映射规则 `fieldMappings[]` 每条（接口级）：`source`（入站字段，`default` 常量注入时可空）、`op`（rename / typeCast / enumMap / default / condition / aggregate）、`target`（出站字段）、`param`（操作参数，如枚举映射表 `PENDING→0, DONE→1` / 默认值 / 条件表达式 / 聚合方式，仅参数化操作需要）、`nullStrategy`（保留原值 / 置空 / 默认值 / 报错）。

错误码映射 `codeMappings[]` 每条：`from`（上游错误码）、`to`（平台错误码）。

配置元数据与「统一内部模型」一一对应，持久化后按 `adapterType + impl + version` 索引；接口级配置覆盖应用级默认（呼应 5.7 与接口级容错）。

## 6. 状态机 / 错误码 / 容错机制

### 6.1 状态机

出站请求状态机（Flow A）：

```mermaid
stateDiagram-v2
    [*] --> INIT
    INIT --> MAPPING: 字段映射
    MAPPING --> SENDING: 调上游
    SENDING --> SUCCESS: 上游成功
    SENDING --> RETRYING: 5xx / 429
    SENDING --> DEAD_LETTER: 4xx（非 429）
    SENDING --> UNKNOWN: 超时 / 连接异常
    RETRYING --> SENDING: 重试（未超上限）
    RETRYING --> COMPENSATING: 重试耗尽
    COMPENSATING --> SUCCESS: 补偿成功
    COMPENSATING --> DEAD_LETTER: 补偿耗尽（超最大次数）
    UNKNOWN --> SUCCESS: 对账确认已到达
    UNKNOWN --> COMPENSATING: 对账确认未到达
    SUCCESS --> [*]
    DEAD_LETTER --> [*]
```

- 状态流：`INIT → MAPPING → SENDING → RETRYING → COMPENSATING → SUCCESS / DEAD_LETTER / UNKNOWN`。
- 5xx/429 → RETRYING（短重试，指数退避，未超上限回 SENDING）；重试耗尽 → COMPENSATING（补偿 worker 兜底）。
- 补偿超过最大次数 → DEAD_LETTER（死信 + 告警）。
- 4xx（非 429）→ DEAD_LETTER，不重试。
- 超时 / 连接异常 → UNKNOWN（结果不确定），对账收敛为 SUCCESS 或 COMPENSATING。
- 熔断器闸门前置：OPEN 时短路径失败（50202，不 incrementAttempt、不触发短重试）——已入队记录顺延 COMPENSATING（冷却后由补偿 worker 重放），**不转死信**（详见 6.4）。

> **落库口径（M5 后状态链）**：设计状态机声明 `SENDING / RETRYING`，但**实际落库状态只有 `INIT → MAPPING → 终态`**——短重试发生在 `@Retryable`（UpstreamInvoker）内部，不逐次写库（`attempt_count` 也不随之递增，仅补偿重放 `incrementAttempt`）。状态链（§4.6）忠实呈现真实落库转移：`SENDING/RETRYING` 不产生节点，短重试次数并入终态节点 detail。这是与上图的有意差异，勿误判为「漏了状态」。

入站送达状态机（Flow B）：

```mermaid
stateDiagram-v2
    [*] --> RECEIVED
    RECEIVED --> ACKED: 送达调用方成功
    RECEIVED --> PENDING: 送达失败（仍回供应商 ack）
    PENDING --> ACKED: 补偿 worker 重送成功
    PENDING --> DEAD_LETTER: 重送耗尽（超最大次数）
    ACKED --> [*]
```

- 状态流：`RECEIVED → ACKED / PENDING`。
- 送达失败 → PENDING（仍回供应商 ack，供应商不重发），由补偿 worker 重送至 ACKED。
- 重送超过最大次数 → DEAD_LETTER（死信 + 告警）。

### 6.2 统一错误码与响应规范

统一响应结构：

```json
{ "code": 0, "msg": "ok", "data": {} }
```

- `code = 0` 成功，非 0 失败；`msg` 人类可读信息；`data` 业务数据（失败时可为空）。

错误码分段：

| code 段 | 含义 | 示例 |
|---|---|---|
| 0 | 成功 | — |
| 401xx | 鉴权失败 | 40100 验签失败（签名不匹配 / 缺头 / 防重放重复）、40101 时间戳超容差、40102 应用未启用、40103 来源 IP 被拒（黑名单命中 / 白名单未命中） |
| 400xx | 参数 / 请求错误 | 40001 参数非法 / 乐观锁冲突、40002 报文错误（格式非法 / 超 1MB / XML 嵌套深度超限） |
| 404xx | 资源不存在 | 40401 接口不存在、40402 应用不存在、40403 接口版本快照不存在（M5 回滚目标校验） |
| 429xx | 限流 / 配额 | 42901 QPS 限流、42902 日配额超限、42903 上游限流（透传上游 429） |
| 502xx | 上游错误 | 50201 上游 5xx / 429 重试耗尽转补偿、50202 熔断短路 |
| 504xx | 上游超时 | 50401 上游读超时 |
| 500xx | 平台内部错误 | 50000 未知异常 |

> 说明：上游返回 429 属限流语义，归入 429xx（42903 透传），重试耗尽则落 50201 转补偿；50202 为平台侧熔断短路（非上游错误）；504xx 与 502xx 的 HTTP 映射遵循「业务码 / 100 = HTTP 状态」约定。

### 6.3 对账 / 补偿机制

- 对账（UNKNOWN 处理）：UNKNOWN = 结果不确定（可能已达上游），不可盲目重试；已成功 → 收敛 SUCCESS，未到达 → 触发补偿 COMPENSATING。M4 现状：人工置位（source=MANUAL）与 TTL 超时自动降级（默认 10 分钟，source=TTL）双来源，均落 reconcile_audit 审计；按上游查询接口自动对账（v1.1）排期未做。
- 补偿：补偿 worker 定时扫描出站 COMPENSATING 记录与入站 PENDING 记录，按固定间隔（如 3s）重试；超过最大次数（如 5 次）转死信 + 告警；补偿重放不重复生效依赖**上游对业务键幂等**（请求携带稳定 biz_id 由上游去重）。

### 6.4 熔断机制

- 目标：上游持续不可用时快速失败，避免反复重试压垮上游、占用线程与连接资源。
- 三态：`CLOSED（正常放行） → OPEN（快速失败） → HALF_OPEN（半开放行探测） → CLOSED / OPEN`。
- 参数：失败率阈值（默认 50%）、滑动窗口 + 最小请求数（默认 10s / 10 次）、熔断时长（默认 30s）、半开探测请求数（默认 2 次）；计数口径 = 每请求一次（@Retryable 内部重试不逐次计数）。
- OPEN：直接快速失败（50202），不触发 `@Retryable` 短重试；入站请求/已入队记录转 COMPENSATING 顺延（不 incrementAttempt，防熔断期空转消耗重试预算），恢复后由补偿 worker 补做；不写死信。
- HALF_OPEN：放行少量探测请求；探测成功恢复 CLOSED，失败回到 OPEN 重新计时。
- 与重试 / 补偿的衔接：熔断先于短重试判断；熔断触发的失败同样落调用日志与告警，冷却结束后自动半开探测。
- 粒度：按「接口 + 供应商」为熔断维度，避免一个坏供应商拖垮所有接口。
