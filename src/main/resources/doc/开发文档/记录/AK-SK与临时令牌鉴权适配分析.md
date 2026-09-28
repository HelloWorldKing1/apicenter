# AK/SK 与「临时令牌（Token）」鉴权方式的适配分析

> 性质：**评估稿（未实施）** · 日期：2026-09-24 · 提出方：使用方提问「有没有 AK+SK 生成 Token 的三方鉴权、以及实时失效的签名；平台怎么适配」
> 依据：**平台代码事实**（`adapter/auth/`、`CredentialOwner`、`OutboundRequestSpec`、`interface_step`、`PreStepExecutor`）
> ＋ **云厂商文档**（阿里云 STS / 腾讯云 GetFederationToken / 华为云 STS AssumeAgency 等，见 §1 备注）
> 结论速览：**有这类方案，且分三类**；**"实时失效"在无状态签名上做不到**，必须靠"短 TTL + 服务端状态"；
> 平台**已具备 3 块地基**，缺 **3 个小能力**（令牌缓存 / 从模型取 token / 401 自动重取），适配路径见 §4。

---

## 0. 先回答两个问题

| 问题 | 答案 |
|---|---|
| **有 AK+SK 生成 Token 的鉴权方式吗？** | **有，而且是云厂商主流**。分三类：① **静态 AK/SK 直签**（Token 就是每请求算出的签名，AK 明文出现在 Credential 里）；② **AK/SK 换临时凭证**（STS：拿回"临时 AK/SK + SessionToken + Expiration"）；③ **AK/SK 换 OAuth2 access_token**（`client_credentials`，最通用的"AK/SK → Token"形态） |
| **签名能实时失效吗？** | **无状态签名不能**（服务端无法"撤回"一个已经合法的签名，只能限制重放窗口）；**要"实时"必须引入服务端状态**：短 TTL + 刷新、撤销列表、或**平台侧凭证吊销**（我们把第三方当调用方时，这条**已经具备**：吊销凭证/停用主体立即生效）。 |

---

## 1. 业界方案对照（真实举例）

> 头名以各云文档为准；本表的重点是**"签名要素"与"失效手段"两列** —— 它们决定平台怎么配。

| 家族 | 谁在用 | 请求里带什么 | Token 形态 | **失效手段** | 换 token 接口 |
|---|---|---|---|---|---|
| **SigV4 + STS** | AWS、MinIO、大部分 S3 兼容 | `Authorization: AWS4-HMAC-SHA256 Credential=<AK>/…, SignedHeaders=…, Signature=…` + `X-Amz-Date`；临时凭证再加 `X-Amz-Security-Token` | 签名（无状态）；STS 时是"临时 AK/SK + SessionToken" | 签名：**只能靠轮换 AK**；STS：`Expiration` 到期失效（**不可主动撤回**，实践靠短 TTL） | `AssumeRole` / `GetSessionToken` |
| **POP / V3** | 阿里云 | `Signature / SignatureMethod / SignatureVersion / Timestamp / Nonce`（RPC 风格），或 V3 的 `Authorization: ACS3-HMAC-SHA256 …`；临时凭证加 `x-acs-security-token` | 签名；STS 时为"AccessKeyId + AccessKeySecret + **SecurityToken** + **Expiration**"（证书/OSS 场景常用） | 同上：签名靠轮换；STS 靠 `Expiration` | STS `AssumeRole`（**主账号 AK 不能调**，须 RAM 用户） |
| **TC3-HMAC-SHA256** | 腾讯云 | `Authorization: TC3-HMAC-SHA256 Credential=…/…, SignedHeaders=…, Signature=…` + `X-TC-Timestamp`；临时凭证加 `X-TC-Token` | 签名；联合身份时为"token + 临时密钥" | 同上 | `GetFederationToken`；官方明确提示"**建议在有效期内重复使用，避免请求该接口频率达到上限被限频**" |
| **SDK-HMAC-SHA256** | 华为云 | `Authorization: SDK-HMAC-SHA256 Access=<AK>, SignedHeaders=…, Signature=…` + `X-Sdk-Date`；临时凭证加会话令牌头 | 签名；STS 为"临时 AK/SK + 会话令牌"（令牌长度不固定，可 <4KB） | 同上 | STS `AssumeAgency` |
| **OAuth2 client_credentials** | 通用（Salesforce/企业微信/自建网关…） | 业务请求带 `Authorization: Bearer <access_token>` | **Token 有 `expires_in`** | **到期自动失效 + 可撤销**（撤销一般需额外接口）；刷新令牌可选 | `POST /token` |
| **平台证书 + 非对称签名** | 微信支付 V3、支付宝 | `Authorization: WECHATPAY2-SHA256-RSA2048 mchid=…,serial_no=…,nonce_str=…,timestamp=…,signature=…` | 签名（RSA2 私钥签名，平台证书验签） | 靠**证书轮换**；另有 `timestamp` 防重放 | —— |

**抽象成两条正交的轴**（平台配置就落在这两条轴上）：

1. **密钥怎么用**：静态密钥直签 ↔ 换短期凭证再签（多一步"换"）
2. **凭证带不带有效期**：无（签名）↔ 有（Token / STS / SAS / presigned URL，均带 `exp`）

---

## 2. 「实时失效」的本质（决定了能承诺什么）

| 机制 | 谁能失效 | 失效延迟 | 代价 |
|---|---|---|---|
| 无状态签名（HMAC/RSA） | 只有"轮换密钥"或"云侧撤销 AK" | 云端撤销：秒级~分钟级；平台侧轮换：**并存窗口（我们默认 24h）** | 无状态、便宜 |
| **STS / 会话令牌** | 到期（`Expiration`）；**主动撤回通常不保证** | = 剩余 TTL（可做到秒级~小时级） | 多一次换 token 调用 + 缓存 |
| **OAuth2 access_token** | 到期 + （部分实现支持）撤销端点 | = 剩余 TTL | 同上 |
| presigned URL / SAS | URL 自带 `Expires`，单次/限时 | = 剩余有效期 | 只能给"单次动作" |
| **平台侧吊销（我们是被调用方）** | **我们说了算** | **立即**（下一请求） | 一次状态读（我们已有：凭证池 ✓） |

> **结论**：对**供应商**（我们当调用方）——**我们无法让它立即失效**，只能"短 TTL + 轮换"，这也是云厂商的设计；
> 对**第三方调用方**（我们当被调用方）——**我们已经能做到立即失效**（凭证池吊销 / 停用主体 → 下一请求即生效 ✓）。

---

## 3. 平台现状映射（代码事实，逐项判定）

| 现有能力 | 落点 | 对 AK/SK 场景 | 结论 |
|---|---|---|---|
| 凭证加密存储 + 轮换 + 遮显 | `app_credential` / `CredentialOwner`（AES-GCM、`ACTIVE`/`ROTATING`/`RETIRED`） | AK/SK 可直接存**复合 JSON**：`{"ak":"…","sk":"…"}`，kind 仍用 `OUTBOUND` | ✅ **无需新 kind、无需新表** |
| 出站请求可加任意头 | `AdapterContext.outbound().header(name, value)`（`ApiKeyAuthAdapter`/`BearerTokenAuthAdapter` 就这么干） | 签名头 / `Bearer <token>` / `X-TC-Token` 都能加 | ✅ |
| 出站 URL 可改写 | `OutboundRequestSpec.url(String)` | 预签名 URL / query 放置签名可行 | ✅ |
| 签名算法 | `HmacSigner`（HMAC-SHA1/256，含常量时间比较） | 覆盖 AWS/阿里/腾讯/华为的 HMAC 家族 | 🟡 **RSA/ECDSA 缺**（JDK 有能力，无适配器） |
| **前置接口编排** | `interface_step` + `PreStepExecutor` + `steps.<code>.*` 引用 | **"先用 AK/SK 换 token"这一步天然可表达**（编排就是"先调 A 拿值、再调 B 用它"） | ✅ 关键复用点 |
| **令牌缓存 / 过期** | **无** | 没有缓存 ⇒ **每个业务请求都会去换一次 token** ⇒ 云厂商会限频（腾讯云文档明确提醒） | ❌ **硬缺口** |
| **从模型/前置步骤取 token 注入头** | **无**：`Bearer/API Key` 适配器只读 `ctx.attrs("outboundCredential")` | 换回来的 token 进不了请求头 | ❌ **硬缺口（改动极小）** |
| 401 / 令牌过期自动重取 | 无：4xx(非 429) 直接**死信**不重试 | 供应商 token 过期时业务直接失败 | ❌ 缺口（可做，但要划边界） |
| **入站方向的实时失效** | 凭证池三级属主 + 单独吊销 + 停用主体 | 调用方侧已经"立即生效" | ✅ 已具备 |
| 入站云风格签名校验 | 现有 4 个 impl（API Key/HMAC/Bearer/IP） | 缺"AK + 云签名头"这一种 | ❌ 小缺口 |

---

## 4. 适配方案（按优先级，含改动面与工时）

### 方案 A（P1，1.5~2 人日）静态 AK/SK **直签**：新增云风格签名适配器

- 新增 `adapter/auth/CloudAkSkSignatureAdapter`（`type=auth`，出站 `OUTBOUND_AUTH` 阶段）
- **params（前端自动渲染，零新增页面）**：`style`（`SIGV4` / `ALIYUN_POP` / `TENCENT_TC3` / `HUAWEI_SDK_HMAC` / `CUSTOM_HMAC`）、
  `akPlacement`（`HEADER` / `QUERY`）、`signedHeaders`、`algorithm`（HMAC-SHA256/1）、`timestampHeader`、`timestampSkewSeconds`、`region`/`service`（SigV4 需要）
- **凭证**：kind `OUTBOUND`，值为 `{"ak":"…","sk":"…"}`（复合 JSON ✓ 已支持）；轮换沿用现有 `ROTATING` 并存 ✓
- **失效能力**：仅"轮换 SK"（平台侧 24h 并存窗口）+ 云侧撤销 AK —— **承诺不了实时**
- **测试（关键）**：**必须用各家官方签名测试向量**做回归（canonical request 差一个空格就全 401，这是最容易翻车处）
- **观察点**：签名失败率、时钟偏差告警

### 方案 B（P2，3~4 人日，**推荐先做这个**）AK/SK → 临时 Token：加「令牌步骤」+ 缓存 + 注入

在**已有的前置编排**上扩展，不新增表：

1. `interface_step` 增 `step_kind`（`HTTP`（默认，今日行为）/ `TOKEN`）与 TOKEN 专用参数（可复用 `params` 风格 JSON 列或加 3 列）：
   `tokenPath`（如 `data.access_token`）、`ttlPath`（如 `data.expires_in`）、`ttlFallbackSeconds`、`refreshAheadSeconds`（默认 60s）
2. **令牌缓存**（内存，`ConcurrentHashMap<stepId, {token, expireAt}>` + **single-flight** 去重并发取令牌）
   —— 与熔断/限流同口径（单实例；多实例各自取 N 份，云厂商限频通常可接受；共享缓存列 P3）
3. **注入**：`BearerTokenAuthAdapter` / `ApiKeyAuthAdapter` 增 `tokenSource`（**模型点路径**，如 `steps.auth.access_token`）：
   存在则优先于凭证（约 5 行）；出站头由适配器写 ✓
4. **失败边界**：换 token 失败 ⇒ 按 `failure_policy=ABORT` ⇒ **链失败、不落运行表**（与编排口径一致 ✓）
5. **可选（P2.5）401 自动重取一次**：仅当"该接口配了 TOKEN 步骤 + 响应为 401/签名失效类" ⇒ 清缓存 → 重取 → **重试 1 次**；
   其余 401 仍走死信（现状）—— **必须写死"仅一次、仅令牌类"**，避免与"4xx 不重试"的定稿冲突
6. **可观测**：`call_log` 记令牌步骤的"命中缓存 / 换发 / 剩余有效期"；指标 `apicenter.token.refresh{step,result}`；
   告警：换发失败率 / 剩余有效期不足（防止风暴式换 token）
7. **测试**：缓存命中不发起 HTTP（计数断言 ✓）、提前刷新、并发 single-flight、TTL 来源（响应字段 vs 兜底）、换发失败走 ABORT、
   mock 一个云厂商 token 端点（WireMock）做端到端

### 方案 C（P2，入站方向）让第三方用云风格签名调平台 + 平台签发短期 token

- **C1（2 人日）** `ClientCloudSignatureVerifyAdapter`（入站验签）：参数化 `style`/`akHeader`/`timestampHeader`/`nonceHeader`/`skewSeconds`；
  **用 AK 定位凭证池里的 SK** —— 这比自报 `X-Client-Id` 更可信（AK 是"凭据指纹"级标识），
  且天然融入凭证池（AK 对应一把 SK，可**单独吊销** ⇒ **实时失效 ✓**）
- **C2（3 人日）** 平台当"STS"：`POST /api/…/client-auth/token`（用 client 凭证换短期 token，TTL 可配 5~60 分钟），
  校验时**每次都读一次客户端/凭证状态** ⇒ **准实时失效**（吊销凭证 ⇒ 最多 1 个请求内失效），不依赖"撤销已签发 token"✗

### 与既有设计的一致性（不破坏定稿）

| 既有定稿 | 本方案是否冲突 |
|---|---|
| 出站签名 = `auth` 适配器 + `app_credential`（M0-04） | ✅ 完全沿用（AK/SK 只是"复合凭证值"+ 新适配器） |
| 状态机：4xx(非 429) → 死信不重试 | ✅ 默认不变；401 重取**仅在配了 TOKEN 步骤时**、且**仅一次**（需单独拍板） |
| 前置编排：运行期零 DDL、失败即链失败 | ✅ 沿用（TOKEN 步骤是"多一种步骤类型"，仍不落运行表） |
| 熔断/限流为**单实例内存**口径 | ✅ 令牌缓存同口径 |
| 敏感数据不落日志 | ✅ 需把新头名注册进 `SensitiveDataMasker`（既有纪律） |

---

## 5. 风险与取舍（实施前必读）

| # | 风险 | 应对 |
|---|---|---|
| 1 | **AK/SK 是"根凭证"**：泄露≈对方账号被接管 | 平台侧只存密文（已有）+ 只回指纹（已有）+ 日志禁落（已有+新增头名注册）；**文档明确建议供应商侧给只读/最小权限** |
| 2 | **签名实现正确性**（canonical request 细节） | **官方测试向量**回归；`style` 分批上线；失败率告警 |
| 3 | **时钟偏差**（时间窗 ±5min 常见） | 参数化 `skewSeconds`；部署侧要求 NTP；偏差告警 |
| 4 | **换 token 接口被限频**（云厂商明确提示） | **缓存是硬要求**（方案 B 第 2 点）+ single-flight + 提前刷新 |
| 5 | **多实例各持一份 token** | 先内存（与熔断同口径）；确需共享再上缓存中间件（P3，当前项目无 Redis） |
| 6 | **可观测不足会导致"风暴式换 token"无人知** | 指标 + `call_log` 留痕（方案 B 第 6 点） |
| 7 | **对"实时失效"的期望管理** | 对供应商侧**承诺不了**（只能短 TTL + 轮换）；对调用方侧**已能**立即失效 |
| 8 | 401 自动重取可能掩盖真实故障 | 仅配了 TOKEN 步骤时生效、仅一次、并记审计 |

---

## 6. 建议落地顺序

1. **方案 B（临时 Token + 缓存 + 注入）** —— 收益最大（覆盖所有"AK/SK 换 token"的云与自建网关），且复用前置编排；
2. **方案 A（静态直签）** —— 覆盖"只给 AK/SK、没有换 token 接口"的供应商（AWS/阿里/腾讯/华为 的签名风格按需逐个加）；
3. **方案 C1（入站云风格验签）** —— 让第三方也能用云方式调平台，且**天然可实时吊销**；
4. **P3**：共享令牌缓存 / 401 自动重取 / RSA·ECDSA 签名（微信支付/支付宝类）。

## 7. 需要你确认的三件事

1. **优先哪家签名风格？**（决定方案 A 的 `style` 首批实现；只要能给一份对方文档/联调账号，我可以照签名测试向量做）
2. **供应商有没有"换 token 接口"？** 有 ⇒ **直接做方案 B**（方案 A 可不做）；只有 AK/SK ⇒ 先做方案 A。
3. **要不要"平台对外签发短期 token 给第三方"（C2）？** 要 ⇒ 需要新增一个公开端点（平台从"被调用方"变成"授权方"），
   我会按"短 TTL + 每次校验读状态"设计（**准实时失效**），并补相应的安全口径与限流。
