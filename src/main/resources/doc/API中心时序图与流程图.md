# API 中心 · 时序图与流程图

> 配合《API中心设计方案.md》，用 Mermaid 描述配置流程、两条链路（出站 Flow A / 入站 Flow B）时序，以及请求处理 + 容错流程图。

## 一、配置流程时序图（新增应用 → 接口配置）

```mermaid
sequenceDiagram
    autonumber
    actor 管理员 as 管理员
    participant App as 应用管理
    participant Auth as 鉴权适配器
    participant Grp as 分组管理
    participant Api as 接口管理
    participant Adp as 适配器配置

    管理员->>App: 新增应用（名称 / 鉴权适配器 / IP 白名单 / IP 黑名单 / 描述）
    App->>Auth: 选择鉴权适配器（API Key / HMAC / 云厂商签名 / OAuth2 / Bearer / Basic / mTLS / 无鉴权）
    Auth-->>App: 绑定应用级鉴权策略 + 签发密钥（appId / appSecret）
    App-->>管理员: 应用创建成功
    管理员->>Grp: 创建分组（应用 → 分组，可管理应用下所有分组）
    Grp-->>管理员: 分组创建成功
    管理员->>Api: 新建接口（应用（供应商）→ 分组；类型 / 方法 / 路径 / 协议 / 上游路径或回调地址）
    管理员->>Api: 配置请求参数（入站 / 出站，Params / Body 双 tab）
    管理员->>Api: 配置字段映射（入站字段 → 出站字段）
    管理员->>Api: 配置响应 / ack 字段（出站响应 / 入站 ack）
    Api-->>管理员: 接口配置完成，发布启用
```

> 鉴权适配器为「应用级默认 + 接口级覆盖」；应用即供应商（归属=上游）；接口归属经应用 → 分组选择；协议适配器按入站/出站协议自动推导；字段映射在接口级配置（入站字段 → 出站字段）；入站回调目标为「回调地址」，出站为「上游路径」。

## 二、出站时序图（Flow A：调用方 → 平台 → 供应商）

```mermaid
sequenceDiagram
    autonumber
    participant 调用方 as 调用方
    participant GW as 平台接口
    participant 链 as 适配器链
    participant SP as 供应商

    Note over GW,链: 链顺序：入站鉴权 → 协议解码 → 报文适配 → 字段映射 → 协议编码 → 出站鉴权
    调用方->>GW: POST /api/orders（X-App-Id / X-Timestamp / X-Signature）
    GW->>链: 入站鉴权（调用方鉴权，平台统一）
    alt 验签失败
        链-->>调用方: 401 鉴权失败
    end
    GW->>链: 协议解码 → 报文适配 → 字段映射 → 协议编码（JSON/XML）
    链->>SP: 调用供应商（按供应商协议）
    alt 成功
        SP-->>链: 响应
        链-->>GW: 反向适配
        GW-->>调用方: 统一响应
    else 5xx / 429
        SP-->>链: 失败 → 短重试（指数退避）→ 耗尽补偿
    else 4xx（非 429）
        SP-->>链: 失败 → 写死信
    else 超时 / 连接异常
        SP-->>链: UNKNOWN → 对账
    end
```

## 三、入站时序图（Flow B：供应商 → 平台 → 调用方）

```mermaid
sequenceDiagram
    autonumber
    participant SP as 供应商
    participant GW as 平台接口
    participant 链 as 适配器链
    participant 调用方 as 调用方

    Note over GW,链: 链顺序：入站鉴权 → 协议解码 → 报文适配 → 字段映射 → 协议编码 → 出站鉴权
    SP->>GW: POST /callback/{appId}/instance-state（X-Partner-Signature / X-Timestamp）
    GW->>链: 入站鉴权（回调验签）
    alt 验签失败
        链-->>SP: 401 签名无效
    end
    GW->>链: 协议解码 → 报文适配 → 字段映射 → 协议编码
    链->>调用方: 送达调用方
    alt 调用方 ack
        调用方-->>链: ack
        链-->>GW: ACKED
        GW-->>SP: 回 ack
    else 送达失败
        链-->>GW: PENDING（仍回 ack，供应商不重发）
        GW-->>SP: 回 ack
        Note over GW: 由补偿 worker 定时重送
    end
```

## 四、请求处理与容错流程图

```mermaid
flowchart TD
    Start([请求进入]) --> Auth{入站鉴权}
    Auth -- 失败 --> Reject[401 拒绝]
    Auth -- 通过 --> Decode[协议解码<br/>JSON/XML → 统一模型]
    Decode --> Msg[报文适配<br/>结构转换]
    Msg --> Map[字段映射<br/>字段级转换]
    Map --> Encode[协议编码<br/>统一模型 → JSON/XML]
    Encode --> OutAuth[出站鉴权<br/>附加调用凭证]
    OutAuth --> Call{调用上游}
    Call -- 成功 --> Resp[反向适配 → 回响应]
    Call -- 5xx/429 --> Retry{短重试<br/>未超上限?}
    Retry -- 是 --> Encode
    Retry -- 否 --> Comp[补偿 worker]
    Call -- 4xx（非 429） --> Dead[死信]
    Call -- 超时/连接异常 --> Unknown[UNKNOWN → 对账]
    Resp --> End([结束])
    Comp -- 补偿成功 --> End
    Comp -- 耗尽（超最大次数） --> Dead
    Dead --> End
    Unknown --> End
```
