# 前置接口编排 · 真实接口案例手动测试（FastMoss + evoLink）

> 版本：v1.0（2026-09-18）｜定位：在**真实第三方接口**上验证「前置步骤（编排）」——真实网络、真实报文、真实鉴权与真实失败码。
> 与《前置接口编排手动验收测试方案.md》的关系：那篇用**本地 WireMock** 三阶段跑通功能面（界面/失败分支/约束/快照）；**本文件是它的真实接口补充篇**，两条链路互相独立，可分别执行。
> 预计耗时：案例 A 约 12 分钟 / 案例 B 约 10 分钟（含抓包）；无凭据走 stub 回放各约 6 分钟。
> 自动化对应：`PreStepIntegrationTest`（14 例，上游为 WireMock）——本文件的价值在**自动化覆盖不到的部分**：真实供应商的鉴权/信封/限流/慢响应，以及「真实报文下命名空间怎么引用」。

---

## 0. 前置条件与凭据策略

| 项 | 说明 |
|---|---|
| 环境 | 后端 8080 / 前端 5173 已起；开发库按 `schema.sql` 建好（20 张表；`interface_step` 与 `call_log.step_code` 已应用） |
| 平台开关 | `app.api-center.pre-step.enabled=true`（默认）；前置响应体上限 `app.api-center.pre-step.max-response-bytes=262144`（默认 256KB） |
| 供应商凭据（可选） | **案例 A**：FastMoss API token（凭证走界面「应用 → 凭证」维护，kind=OUTBOUND）<br>**案例 B**：evoLink API key（`sk-…`） |
| **无凭据路径** | 用**真实报文的 stub 回放**（附录 A 提供注册命令）：除「真实网络」外行为完全一致，本文所有断言同样成立 |
| 密钥纪律 | token/key 只存在于你本地与平台凭证库（AES-256-GCM 加密）；**本文件、仓库、日志永不出现明文**；管理面仅显尾 4 指纹；如曾泄露立即去控制台吊销重建 |
| 计费与限流 | 两者均为**计费**外部依赖：手点 1~2 次即可，**勿循环/压测** |
| 界面报 `Invalid CORS request` | 用 `127.0.0.1:5173`（或 Vite 端口自增后的 5174）打开前端时，旧版 CORS 白名单会回 403；2026-09-18 起默认允许本机回环任意端口，**重启后端生效**（详见《API中心使用教程》FAQ Q0） |
| **数组下标限制（务必先读）** | M0-02 定稿 D1：`source` **不支持数组下标寻址**（`list[0].nickname` 不可用）。数组可**整块**搬移（`rename` 支持任意节点），元素级提取列为 v1.1。**本文两个案例都按此约束设计**，并在 §3 给出踩坑表现 |

### 0.1 先抓一次真实响应，锚定字段名（真实凭据路径必做）

供应商响应字段名以真实返回为准；先直连 curl 抓一次，再按抓到的字段名填后面的映射表：

```bash
# A) FastMoss 达人列表（真实 token）——seed/开发计划 §2.4 已固化的期望形态如下
curl -s -X POST 'https://openapi.fastmoss.com/shop/v1/creatorList' \
  -H 'Content-Type: application/json' -H "Authorization: Bearer <你的 token>" \
  -d '{"filter":{"seller_id":"7494312521977267257"},"orderby":[{"field":"units_sold","order":"desc"}],"page":1,"pagesize":1}'
# 期望：{"code":0,"data":{"total":822,"list":[{"seller_id":"7494312521977267257","uid":"…",
#        "unique_id":"megandd1","nickname":"megan!","region":"US","category_id":4,
#        "units_sold":1135,"gmv":70283.55}]},"message":"","timestamp":…,"request_id":"…"}

# B) evoLink 建生图任务（真实 key；**异步**：立即返回任务 id，完成靠回调或轮询）
curl -s -X POST 'https://api.evolink.ai/v1/images/generations' \
  -H 'Content-Type: application/json' -H "Authorization: Bearer <你的 key>" \
  -d '{"model":"gpt-image-2","prompt":"一只微笑的可爱小猫"}'
# 期望：返回任务 id（**字段名以真实响应为准**，例如 id / data.id）；状态轮询 GET /v1/tasks/{id}
```

> 关键前提：**B（前置接口）的 RESP 白名单决定 `steps.*` 里能引用什么**。FastMoss 走信封适配
>（`ADP-201`：`envelope=data, codeField=code, successValue=0`），所以 step 输出 = 信封内 `data`
> ⇒ 可引用 `steps.fm.total`（标量）与 `steps.fm.list`（数组整块）；`steps.fm.code/message` 不在其中（被信封剥掉）。

---

## 1. 案例 A：FastMoss 达人列表作为前置 → 第三方（`A → B(FastMoss) → 第三方`）

一句话：新接口 A 在打自己供应商之前，**先复用已维护的 FastMoss 接口**取到达人数据（总数 + 列表），
把结果映射进 A 的出站报文，再交给第三方。典型用途：给下游报表/生图服务提供「已验证过的达人数据」。

### 1.1 确认前置接口 B（复用 seed，无需新建）

`POST /api/admin/seed/import` 后应存在（不存在则在界面上按 开发计划 §2.2 建）：

| 项 | 值 |
|---|---|
| 接口 | `IF-FM-001`「FastMoss 达人列表」，**已发布** |
| 平台路径 → 供应商路径 | `/fastmoss/creatorList` → `/shop/v1/creatorList` |
| 应用 `fastmoss` | 服务地址 `https://openapi.fastmoss.com`；出站签名 `ADP-101`（Bearer）；报文适配 `ADP-201`（信封 `data`） |
| 凭证 | `app_credential` kind=OUTBOUND（seed 为占位 token，**真实联调时在「应用 → 凭证」更新为真 token**） |

> 无凭据路径：把应用 `fastmoss` 的**服务地址**临时改成 `http://localhost:18080`（stub），并按附录 A 注册 `/shop/v1/creatorList`；跑完记得改回。

### 1.2 新建第三方应用与接口 A

| 步骤 | 操作 |
|---|---|
| ① 建应用 | 应用管理 → 新建：`appId=BRIEF`、名称 `简报供应商`、**服务地址 = 第三方根地址**（stub：`http://localhost:18080`；真实外网可用 `https://httpbin.org`）→ 启用（凭证不需要，第三方无鉴权） |
| ② 建分组 | 在 `BRIEF` 下建「默认分组」 |
| ③ 建接口 A | 标识 `IF-BRIEF-001`、名称「达人选品简报」、类型**出站中转**、方法 POST、平台路径 `/brief/create`、供应商路径 `/brief/report`（httpbin 用 `/post`）、协议 JSON/JSON、**读超时 5000**（前置可能稍慢）、最大重试 4 |
| ④ 入站参数（IN） | `seller_id`(string, 必填, 示例 `7494312521977267257`)、`prompt`(string, 示例 `达人简报`) |
| ⑤ 出站参数（OUT） | `creator_total`(number)、`creators`(array)、`seller_id`(string)、`prompt`(string) |
| ⑥ **前置步骤** Tab | `＋ 添加前置步骤` → 步骤名 **`fm`**、前置接口选 `IF-FM-001`、策略「阻断后续（ABORT）」、启用 |
| ⑦ 字段映射 Tab | 见下表 4 条（**注意第 2 条用 condition 守门**） |
| ⑧ 发布 | 列表 → 发布 |

**字段映射（A 的 IN + `steps.fm.*` → 第三方报文）**

| # | source | op | target | param | nullStrategy | 说明 |
|---|---|---|---|---|---|---|
| 1 | `steps.fm.total` | `typeCast` | `creator_total` | `INT` | `KEEP` | 前置输出是 JSON number，这里显式转 INT，演示类型收敛 |
| 2 | `steps.fm.list` | `condition` | `creators` | `steps.fm.total > 0` | `NULL` | **数组整块**搬移（D1 限制下的正解）；条件不满足则省略该字段 |
| 3 | `seller_id` | `rename` | `seller_id` | — | `KEEP` | 宿主**自己的入站字段**（用于验证「宿主模型未被前置污染」） |
| 4 | `prompt` | `rename` | `prompt` | — | `KEEP` | 同上，纯透传字段 |

### 1.3 调用与期望

```bash
curl -s -X POST http://localhost:8080/brief/create -H 'Content-Type: application/json' \
  -d '{"seller_id":"7494312521977267257","prompt":"达人简报"}' -H 'X-Trace-Id: tr-brief-1'
```

期望（stub 路径：用附录 A 的快照报文，数字与 FastMoss 真实一致）：

| 检查点 | 期望 |
|---|---|
| 平台响应 | `{"code":0,"msg":"ok","data":{…}}`（A 的 RESP 未声明 → 不过滤，原样回第三方响应） |
| 第三方实际收到 | `{"creator_total":822,"creators":[{"seller_id":"7494312521977267257","unique_id":"megandd1","nickname":"megan!","units_sold":1135,"gmv":70283.55,…}],"seller_id":"7494312521977267257","prompt":"达人简报"}` |
| 第三方报文**不含** | `steps` 键（保留键已在 ENCODE 前剥离） |
| 前置 B 实际收到 | A 的入站报文原样：`{"seller_id":"…","prompt":"达人简报"}`（**不含** `steps`：模型隔离） |
| Monitor → 调用日志（按 traceId `tr-brief-1` 过滤） | 共 **3 条**：① `IN`（interface=IF-BRIEF-001）② `OUT`（interface=**IF-FM-001**，**「步骤」列 = `前置·fm`**）③ `OUT`（interface=IF-BRIEF-001） |
| 步骤筛选 | 调用日志「前置步骤名」填 `fm` → 只剩第 ② 条 |
| 脱敏 | 第 ② 条的请求头里 `Authorization: Bea*****`（**无明文 token**） |
| 状态机详情（出站记录） | 状态链 = `INIT` → **`前置步骤` 节点（trigger=PRE_STEP，detail 含 `fm HTTP 200 …ms code=0`）** → `MAPPING` → `SUCCESS` |
| **B 不落独立运行记录** | `SELECT COUNT(*) FROM outbound_request WHERE interface_id = (SELECT id FROM interface WHERE code='IF-FM-001')` → **0**（前置不落子记录，避免孤儿补偿重放） |
| **前置调用不过接入层防护（可判别）** | 给**前置所属应用 `fastmoss`** 设 `ip_whitelist=10.0.0.1`：<br>① 直接调 B 的平台路径 `/fastmoss/creatorList` → 应 **40103 来源 IP 被拒**（接入层防护生效）；<br>② 调 A（前置走内部直调）→ **成功**（前置不经 GatewayGuard，也不消耗/占用任何应用的配额）；<br>跑完清空 `fastmoss` 的 `ip_whitelist` |
| 慢响应与超时口径 | 把 `IF-FM-001` 的读超时改成 `300ms`（stub 加固定延迟 1500ms）→ A 转 `UNKNOWN`（50401）；改回 3000ms 恢复 |

### 1.4 无凭据 stub 回放（可选，等价执行）

见 **附录 A**：注册 `/shop/v1/creatorList`（FastMoss 真实报文快照）+ `/brief/report`（第三方捕获桩），
并把 `fastmoss` 应用服务地址临时改成 `http://localhost:18080`。之后 §1.3 的检查点逐条同样适用。

---

## 2. 案例 B：evoLink 生图作为前置（取任务 id）→ 第三方

一句话：A 收到调用方的生图需求（`model`/`prompt`），**前置调用 evoLink 建任务拿到 task id**，再把 task id 交给第三方（例如下游的「素材入库」服务）。
典型用途：**前置负责「发起」，第三方负责「登记/分发」**；evoLink 完成图生成后的回调/轮询属业务侧（平台可用另一条入站回调链路承接）。

### 2.1 准备前置接口 B（evoLink 建任务）

| 步骤 | 操作 |
|---|---|
| ① 建应用 | `appId=EVOLINK`、名称 `evoLink 生图`、服务地址 `https://api.evolink.ai` → 启用 |
| ② 出站凭证 | 应用 → 凭证卡片 → 选 `Bearer Token` 适配器 → 填真实 API key（`sk-…`）→ 保存（界面只显尾 4 指纹） |
| ③ 建接口 B | 标识 `IF-EVL-GEN`、名称「evoLink 建生图任务」、出站中转、POST、平台路径 `/ai/gen`、供应商路径 `/v1/images/generations`、JSON/JSON、**读超时 10000**（保守值）、最大重试 4 |
| ④ 参数与映射 | **全部留空（透传）**：B 收到什么就原样发给 evoLink ⇒ A 的入站报文天然成为 evoLink 请求体 |
| ⑤ 发布 B | 必须**已发布**，否则 A 保存时会被拦（D-PS-8） |

> 无凭据路径：服务地址临时改 `http://localhost:18080`，按附录 A 注册 `/v1/images/generations`（真实响应形态的桩），凭证可留空（stub 不校验签名）。

### 2.2 新建接口 A 并挂前置

| 步骤 | 操作 |
|---|---|
| ① 建应用 | `appId=POSTER`、名称 `素材登记`、服务地址 = 第三方（stub `http://localhost:18080`；真实外网 `https://httpbin.org`）→ 启用 |
| ② 建接口 A | 标识 `IF-POSTER-001`、名称「海报生成登记」、出站中转、POST、平台路径 `/poster/publish`、供应商路径 `/publish`（httpbin 用 `/post`）、JSON/JSON、读超时 5000 |
| ③ 入站参数（IN） | `model`(string, 示例 `gpt-image-2`)、`prompt`(string, 示例 `一只微笑的可爱小猫`) |
| ④ 出站参数（OUT） | `task_id`(string)、`model`(string)、`prompt`(string) |
| ⑤ **前置步骤** | 步骤名 **`gen`**、前置接口 `IF-EVL-GEN`、策略 ABORT、启用 |
| ⑥ 字段映射 | `steps.gen.<任务id字段>` → `task_id`（rename，KEEP）<br>`prompt` → `prompt`（rename，KEEP）<br>`model` → `model`（rename，KEEP）<br>⚠ `<任务id字段>` **以 §0.1 抓到的真实字段名为准**（如 `id`）；写错会得到 `task_id: null`（KEEP）或 40001（ERROR），见 §3 |
| ⑦ 发布 A | — |

### 2.3 调用与期望

```bash
curl -s -X POST http://localhost:8080/poster/publish -H 'Content-Type: application/json' \
  -d '{"model":"gpt-image-2","prompt":"一只微笑的可爱小猫"}' -H 'X-Trace-Id: tr-poster-1'
```

| 检查点 | 期望 |
|---|---|
| 平台响应 | `code=0`（A 的 RESP 未声明 → 不过滤） |
| 前置 evoLink 实际收到 | `{"model":"gpt-image-2","prompt":"一只微笑的可爱小猫"}`（B 透传 A 的入站报文，含 `Authorization: Bearer sk-****`） |
| 第三方实际收到 | 含 `task_id`（= 前置返回的任务 id）与 `prompt`/`model`；**不含** `steps` |
| Monitor | 同 traceId 可见：A 的 IN / B 的 OUT（步骤列 `前置·gen`）/ A 的 OUT；状态链含 `前置步骤` 节点 |
| 边界 | evoLink 异步：**前置只同步拿 task id**；图片生成结果由回调（平台另建入站接口）或业务侧轮询 `GET /v1/tasks/{id}` 获取——**接口编排不负责轮询** |

---

## 3. 最容易踩的坑（真实案例高频）

| 现象 | 原因 | 处理 |
|---|---|---|
| `task_id` / `creator_total` 为 `null`（或 40001「字段缺失」） | 引用了**数组下标**（`steps.fm.list[0].nickname`）——D1 不支持，会把 `list[0]` 当**字面字段名**去找 → 找不到 → 走 null_strategy | 数组**整块**搬移（`creator_total` 取标量、`creators` 取整块）；元素级提取等 v1.1 |
| 引用了信封外的字段（如 `steps.fm.code`）报 40001 | B 走信封适配（`envelope=data`），step 输出只有 `data` 里的字段 | 只引用 `data` 内字段（`total`/`list`/…） |
| `401` → `40001 前置步骤 fm 失败：上游拒绝（HTTP 401）` | token 过期/未配置 | 应用 → 凭证 → 更新后重试（无重启，凭证每请求实时读） |
| `50401 UNKNOWN` | 前置读超时（FastMoss 真实网络偶发慢） | 查 B 的读超时（默认 3000ms）→ 调大；UNKNOWN 需人工对账置位（**不会自动重试**，这是有意语义） |
| `50201`/`50202` → A `COMPENSATING` | 前置 5xx / 429 耗尽 或 B 熔断 OPEN | 等补偿 worker 重放（≤3s 一轮）；重放会**重跑前置**（依赖供应商幂等，ADR 5） |
| `40001 目标接口未发布` | B 被下线 | 重新发布 B；注意下线时服务端会返回 `warnings[]` 强提示引用方 |
| `40001 前置链长度超限` | `A→B→C→D`（节点数 > 3） | 收敛层级，或改为「B 自己不做前置」 |
| `40001 前置响应体超过上限` | 前置响应 > 256KB（FastMoss `pagesize` 调大即可复现） | 让 B 裁剪字段/缩小 `pagesize`，或调 `pre-step.max-response-bytes`（**不截断**是刻意选择） |
| A 的入站字段变成 `null` | 🔴 历史缺陷（已修）：`withoutSteps` 共享实例导致前置映射写回宿主载体 | 已修复并有用例守护（`PreStepIntegrationTest#前置接口自身带映射_不得污染宿主模型`）；若在旧版本复现，升级即可 |

---

## 4. 收尾与清理

```bash
# 1) 停用演示应用（避免误调用计费接口）
#    应用管理 → BRIEF / POSTER / (EVOLINK) → 停用；或直接删（无接口时）

# 2) 恢复被临时改动的配置
#    - fastmoss 应用「服务地址」改回 https://openapi.fastmoss.com（若走 stub 路径）
#    - IF-FM-001 读超时改回 3000ms；BRIEF 的 qps_limit 清空（若做过 §1.3 配额验证）
#    - EVOLINK 服务地址改回 https://api.evolink.ai（若走 stub 路径）

# 3) 清桩与请求计数
curl -s -X POST http://localhost:18080/__admin/reset
```

```sql
-- 4) 清演示应用的运行数据与配置（按需替换 app_id；顺序：先运行数据再配置子表）
DELETE FROM outbound_request_state_log WHERE outbound_request_id IN
  (SELECT id FROM outbound_request WHERE app_id IN ('BRIEF','POSTER'));
DELETE FROM dead_letter WHERE biz_type = 'OUTBOUND' AND ref_id IN
  (SELECT id FROM outbound_request WHERE app_id IN ('BRIEF','POSTER'));
DELETE FROM outbound_request WHERE app_id IN ('BRIEF','POSTER');
DELETE FROM interface_step           WHERE interface_id IN (SELECT id FROM interface WHERE app_id IN ('BRIEF','POSTER'));
DELETE FROM interface_snapshot       WHERE interface_id IN (SELECT id FROM interface WHERE app_id IN ('BRIEF','POSTER'));
DELETE FROM interface_param          WHERE interface_id IN (SELECT id FROM interface WHERE app_id IN ('BRIEF','POSTER'));
DELETE FROM interface_body           WHERE interface_id IN (SELECT id FROM interface WHERE app_id IN ('BRIEF','POSTER'));
DELETE FROM interface_field_mapping  WHERE interface_id IN (SELECT id FROM interface WHERE app_id IN ('BRIEF','POSTER'));
DELETE FROM interface_field_def      WHERE interface_id IN (SELECT id FROM interface WHERE app_id IN ('BRIEF','POSTER'));
DELETE FROM interface_adapter_binding WHERE interface_id IN (SELECT id FROM interface WHERE app_id IN ('BRIEF','POSTER'));
DELETE FROM interface WHERE app_id IN ('BRIEF','POSTER');
DELETE FROM app_group WHERE app_id IN ('BRIEF','POSTER');
DELETE FROM app WHERE app_id IN ('BRIEF','POSTER');
```

---

## 5. 退出检查表

**案例 A**
- [ ] 前置 B 真被调用（B 侧有请求 / stub 计数 +1），且 B 收到的入参不含 `steps`；
- [ ] 第三方报文含 `creator_total=822` 与整块 `creators` 数组、`seller_id` 来自**宿主入站报文**；
- [ ] 第三方报文不含 `steps`；
- [ ] 调用日志 3 条同 traceId、B 的 OUT 条「步骤」列 `前置·fm`、可按键 `fm` 筛选；
- [ ] B 的 `Authorization` 头已脱敏；
- [ ] 状态链含 `前置步骤` 节点、终态 `SUCCESS`；
- [ ] `outbound_request` 中**没有** B 的独立记录；
- [ ] 前置调用不过接入层防护：`fastmoss` 设 `ip_whitelist=10.0.0.1` 时，直调 B 报 40103、经 A 的前置调用仍成功（跑完清空白名单）。

**案例 B**
- [ ] evoLink 收到 `{model,prompt}`（A 的入站报文透传），头 `Authorization: Bearer sk-****`；
- [ ] 第三方收到 `task_id`（= 前置返回的任务 id）；
- [ ] 调用日志与状态链同 A（步骤名 `gen`）；
- [ ] 明确记录：**异步完成由回调/轮询承担，不在编排范围**。

---

## 附录 A · 无凭据 stub 回放（真实报文快照）

```bash
# A1) FastMoss 达人列表（报文与开发计划 §2.4 / M2 黄金用例一致；数字与真实一致）
curl -s -X POST http://localhost:18080/__admin/mappings -d @- <<'JSON'
{"request":{"method":"POST","urlPath":"/shop/v1/creatorList"},
 "response":{"status":200,"headers":{"Content-Type":"application/json"},
 "jsonBody":{"code":0,"data":{"total":822,"list":[{"seller_id":"7494312521977267257","uid":"6682898641256350725",
   "unique_id":"megandd1","nickname":"megan!","region":"US","category_id":4,"units_sold":1135,"gmv":70283.55}]},
   "message":"","timestamp":1788252017,"request_id":"b83de1e8-88b9-fc53-28a5-1f4872029fea"}}}
JSON

# A2) 第三方捕获桩（案例 A：/brief/report）
curl -s -X POST http://localhost:18080/__admin/mappings -d @- <<'JSON'
{"request":{"method":"POST","urlPath":"/brief/report"},
 "response":{"status":200,"headers":{"Content-Type":"application/json"},"jsonBody":{"ok":true}}}
JSON

# B1) evoLink 建任务（**字段名以真实响应为准**；此处为任务 id 的通用形态桩）
curl -s -X POST http://localhost:18080/__admin/mappings -d @- <<'JSON'
{"request":{"method":"POST","urlPath":"/v1/images/generations"},
 "response":{"status":200,"headers":{"Content-Type":"application/json"},
 "jsonBody":{"id":"task-stub-9f3c1a","status":"pending","created":1788252017}}}
JSON

# B2) 第三方捕获桩（案例 B：/publish）
curl -s -X POST http://localhost:18080/__admin/mappings -d @- <<'JSON'
{"request":{"method":"POST","urlPath":"/publish"},
 "response":{"status":200,"headers":{"Content-Type":"application/json"},"jsonBody":{"ok":true}}}
JSON

# 查看第三方实际收到的报文
curl -s -X POST http://localhost:18080/__admin/requests/find -d '{"urlPath":"/brief/report"}'
curl -s -X POST http://localhost:18080/__admin/requests/find -d '{"urlPath":"/publish"}'
```

> stub 路径需把被测**前置接口所属应用的服务地址**临时指向 `http://localhost:18080`（案例 A 改 `fastmoss`、案例 B 改 `EVOLINK`），
> 第三方应用（`BRIEF`/`POSTER`）本来就指向 18080 或 httpbin。跑完按 §4 恢复。

---

## 附录 B · 相关文档

| 文档 | 用途 |
|---|---|
| 《前置接口编排手动验收测试方案.md》 | 本地 stub 三阶段（界面/失败分支/约束/快照/开关）——功能面 |
| 《前置接口编排设计方案.md》 | 能力语义与边界（D-PS 决策、失败传播矩阵、保留键、D-PS-11 预算） |
| 《整体测试方案.md》§6.5 / §6.6 | evoLink 全流程（含 Flow B 回调 + 公网 HTTPS）与真实 XML 端点（可选联调） |
| 《整体测试方案.md》§8.5 | 编排用例 O1–O4（stub 版验收卡） |
| 《API中心使用教程.md》§7.5 | 界面操作 + 等价 curl + 运行语义表（使用者视角） |
| 《技术踩坑记录.md》§12 | 事务边界与 `UnifiedModel` 共用实例两条真坑 |
