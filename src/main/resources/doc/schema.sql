-- ============================================================
-- API 中心（现行设计）· 建表脚本
-- 依据《表结构设计.html》生成，共 16 张表：配置类 11 + 运行类 5
-- 目标库：MySQL 5.7 / 8.0 InnoDB（双兼容），字符集 utf8mb4
-- 注意：与 doc_old/schema.sql（旧版 ERP demo 9 表）不是同一套，勿混用
-- 不使用数据库外键约束：引用完整性由应用层保证，引用列均建索引（见各表）
-- ============================================================

-- 建库（首次部署执行；库名 / 排序规则可按部署环境调整）
-- collation 用 general_ci：MySQL 5.7 / 8.0 双兼容（0900_ai_ci 为 8.0 专属，5.7 会报错）
CREATE DATABASE IF NOT EXISTS apicenter
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE apicenter;

-- 01 应用（供应商）：出站签名凭证与回调验签凭证两类分离（凭证落 16 号表 app_credential）；鉴权/报文适配器按 id 引用 adapter 表
CREATE TABLE app (
    app_id                      VARCHAR(32)  PRIMARY KEY COMMENT '全局唯一应用标识（如 TENCENT-CLOUD）',
    name                        VARCHAR(64)  NOT NULL COMMENT '应用名称',
    contact                     VARCHAR(64)  COMMENT '联系人',
    auth_adapter_id             VARCHAR(16)  COMMENT '出站供应商签名适配器（NULL = 无鉴权）',
    callback_auth_adapter_id    VARCHAR(16)  COMMENT '回调验签适配器（NULL = 无鉴权），仅入站回调接口生效',
    default_message_adapter_id  VARCHAR(16)  COMMENT '默认报文适配器（NULL = 平台默认）；接口级绑定可覆盖',
    base_url                    VARCHAR(255) COMMENT '服务地址（供应商 API 根地址，出站接口的上游路径拼于此）',
    ip_whitelist                VARCHAR(500) COMMENT '来源 IP 白名单（英文逗号分隔，空 = 不限制）',
    ip_blacklist                VARCHAR(500) COMMENT '来源 IP 黑名单',
    qps_limit                   INT          COMMENT 'QPS 限流（空/0 = 不限）',
    daily_quota                 BIGINT       COMMENT '日调用量上限',
    status                      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '生命周期：DRAFT/ENABLED/DISABLED/CANCELLED',
    `desc`                      VARCHAR(500) COMMENT '描述',
    created_at                  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_app_auth_adapter (auth_adapter_id),
    KEY idx_app_callback_adapter (callback_auth_adapter_id),
    KEY idx_app_msg_adapter (default_message_adapter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用（供应商）';

-- 02 分组：应用下的组织单元，纯归类/展示用，不承载配置
CREATE TABLE app_group (
    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    app_id      VARCHAR(32) NOT NULL COMMENT '所属应用',
    name        VARCHAR(64) NOT NULL COMMENT '分组名称（应用内唯一）',
    sort_order  INT         NOT NULL DEFAULT 0 COMMENT '排序',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_group (app_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分组';

-- 03 接口定义：出站中转 / 入站回调；目标地址随类型互斥；参数、映射、响应·ack、适配器绑定落子表
CREATE TABLE interface (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(16)  NOT NULL COMMENT '接口标识（如 IF001），对外展示与引用',
    name          VARCHAR(64)  NOT NULL COMMENT '接口名称',
    if_type       VARCHAR(16)  NOT NULL COMMENT 'OUTBOUND 出站中转 / INBOUND 入站回调',
    method        VARCHAR(8)   NOT NULL COMMENT 'HTTP 方法（POST/GET/PUT/DELETE）',
    path          VARCHAR(255) NOT NULL COMMENT '平台侧路径（路由键，全局唯一）',
    protocol_in   VARCHAR(8)   NOT NULL DEFAULT 'JSON' COMMENT '入站协议（来源 → 平台）：JSON/XML',
    protocol_out  VARCHAR(8)   NOT NULL DEFAULT 'JSON' COMMENT '出站协议（平台 → 目标）：JSON/XML',
    app_id        VARCHAR(32)  NOT NULL COMMENT '归属应用（供应商，既是归属也是上游）',
    group_id      BIGINT       NOT NULL COMMENT '归属分组（须属于所选应用）',
    upstream_path VARCHAR(255) COMMENT '出站上游路径（相对路径，拼 app.base_url；仅 OUTBOUND）',
    callback_url  VARCHAR(255) COMMENT '入站回调地址（送达目标 URL；仅 INBOUND，必填）',
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/OFFLINE；下线后停止路由',
    version       INT          NOT NULL DEFAULT 1 COMMENT '配置版本（变更生成新版本，见 interface_snapshot）',
    timeout_ms    INT          NOT NULL DEFAULT 3000 COMMENT '读超时（出站=调上游；入站=回调地址调用）',
    max_retries   INT          NOT NULL DEFAULT 4 COMMENT '短重试最大次数（补偿上限见 outbound_request.max_attempts）',
    `desc`        VARCHAR(500) COMMENT '描述',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_interface_code (code),
    UNIQUE KEY uk_interface_path (path),
    KEY idx_interface_app (app_id),
    KEY idx_interface_group (group_id),
    KEY idx_interface_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口定义';

-- 04 接口配置快照：版本化支撑（回滚 / 灰度）
CREATE TABLE interface_snapshot (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    interface_id BIGINT       NOT NULL COMMENT '所属接口',
    version      INT          NOT NULL COMMENT '版本号',
    config_json  LONGTEXT     NOT NULL COMMENT '整接口定义快照（参数/Body/字段映射/响应·ack/适配器绑定）',
    change_note  VARCHAR(255) COMMENT '变更说明',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间',
    UNIQUE KEY uk_snapshot (interface_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口配置快照';

-- 05 请求参数：入站侧（来源→平台）与出站侧（平台→目标）两侧；字段映射 source/target 从此表取值
CREATE TABLE interface_param (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    interface_id BIGINT       NOT NULL COMMENT '所属接口',
    side         VARCHAR(8)   NOT NULL COMMENT 'IN 入站侧 / OUT 出站侧（入站回调的 OUT = 送达报文）',
    name         VARCHAR(64)  NOT NULL COMMENT '参数名（同侧唯一）',
    type         VARCHAR(16)  NOT NULL DEFAULT 'string' COMMENT 'string/number/boolean/object/array',
    required     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否必填',
    sample       VARCHAR(255) COMMENT '示例值',
    sort_order   INT          NOT NULL DEFAULT 0 COMMENT '排序',
    UNIQUE KEY uk_param (interface_id, side, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='请求参数';

-- 06 请求体：每侧一个 Body 配置
CREATE TABLE interface_body (
    id           BIGINT      AUTO_INCREMENT PRIMARY KEY,
    interface_id BIGINT      NOT NULL COMMENT '所属接口',
    side         VARCHAR(8)  NOT NULL COMMENT 'IN / OUT（与 interface_param.side 对应）',
    body_type    VARCHAR(32) NOT NULL DEFAULT 'none' COMMENT 'none/form-data/x-www-form-urlencoded/json/xml',
    raw          LONGTEXT    COMMENT 'Body 原始模板（json/xml 时使用）',
    form         LONGTEXT    COMMENT 'form-data 字段列表（JSON 数组）',
    UNIQUE KEY uk_body (interface_id, side)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='请求体';

-- 07 字段映射（接口级，入站 → 出站）：运行时规则，由映射引擎按 op 解释执行
CREATE TABLE interface_field_mapping (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    interface_id  BIGINT       NOT NULL COMMENT '所属接口',
    source        VARCHAR(64)  COMMENT '入站字段（op=default 常量注入时可空）',
    op            VARCHAR(16)  NOT NULL COMMENT 'rename/typeCast/enumMap/default/condition/aggregate',
    target        VARCHAR(64)  NOT NULL COMMENT '出站字段',
    param         VARCHAR(500) COMMENT '操作参数（枚举映射表/默认值/条件表达式/聚合方式/目标类型）；参数化操作必填',
    null_strategy VARCHAR(16)  NOT NULL DEFAULT 'KEEP' COMMENT 'KEEP 保留原值 / NULL 置空 / DEFAULT 默认值 / ERROR 报错',
    sort_order    INT          NOT NULL DEFAULT 0 COMMENT '执行顺序',
    KEY idx_mapping_interface (interface_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段映射';

-- 08 响应 / ack 字段：出站=出站响应字段（RESP），入站=ack 回执字段（ACK）
CREATE TABLE interface_field_def (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    interface_id BIGINT       NOT NULL COMMENT '所属接口',
    kind         VARCHAR(8)   NOT NULL COMMENT 'RESP 出站响应字段（仅 OUTBOUND）/ ACK ack 回执字段（仅 INBOUND）',
    name         VARCHAR(64)  NOT NULL COMMENT '字段名',
    type         VARCHAR(16)  NOT NULL DEFAULT 'string' COMMENT '字段类型',
    `desc`       VARCHAR(255) COMMENT '字段说明',
    sort_order   INT          NOT NULL DEFAULT 0 COMMENT '排序',
    UNIQUE KEY uk_field_def (interface_id, kind, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='响应 / ack 字段';

-- 09 适配器定义：三类（鉴权/协议/报文）；无状态、配置驱动；凭证类参数不落此处，统一存 app
CREATE TABLE adapter (
    id         VARCHAR(16) PRIMARY KEY COMMENT '适配器标识（如 ADP-001）',
    name       VARCHAR(64) NOT NULL COMMENT '适配器名称（展示用，建议唯一；绑定一律按 id 引用）',
    type       VARCHAR(16) NOT NULL COMMENT 'auth 鉴权 / protocol 协议 / message 报文',
    impl       VARCHAR(64) NOT NULL COMMENT '实现类（HmacAuthAdapter / JsonProtocolAdapter / EnvelopeMessageAdapter 等）',
    enabled    TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '启用 / 停用',
    version    VARCHAR(16) NOT NULL DEFAULT '1.0' COMMENT '版本（版本化 / 灰度）',
    params     LONGTEXT    COMMENT '参数 JSON（按 impl 元数据 schema；凭证类参数不落此处）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_adapter_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='适配器定义';

-- 10 接口-适配器绑定：MESSAGE/AUTH/CALLBACK_AUTH 三角色；NULL = 继承应用默认；协议适配器自动推导不进本表
CREATE TABLE interface_adapter_binding (
    id           BIGINT      AUTO_INCREMENT PRIMARY KEY,
    interface_id BIGINT      NOT NULL COMMENT '所属接口',
    `role`       VARCHAR(16) NOT NULL COMMENT 'MESSAGE 报文 / AUTH 供应商签名（仅出站）/ CALLBACK_AUTH 回调验签（仅入站）',
    adapter_id   VARCHAR(16) COMMENT '绑定的适配器；NULL = 继承应用默认',
    version      VARCHAR(16) COMMENT '指定适配器版本（灰度切换；空 = 用适配器当前启用版本）',
    UNIQUE KEY uk_binding (interface_id, `role`),
    KEY idx_binding_adapter (adapter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口-适配器绑定';

-- 11 出站请求（Flow A 状态机载体）：status 即状态机；补偿/对账 worker 按 (status, next_retry_at) 扫描
CREATE TABLE outbound_request (
    id            BIGINT      AUTO_INCREMENT PRIMARY KEY,
    interface_id  BIGINT      NOT NULL COMMENT '所属接口',
    app_id        VARCHAR(32) NOT NULL COMMENT '供应商应用',
    biz_id        VARCHAR(64) NOT NULL COMMENT '业务键（请求携带稳定 biz_id，上游据此去重）',
    in_payload    LONGTEXT    COMMENT '入站原始报文（统一内部模型序列化）',
    out_payload   LONGTEXT    COMMENT '映射后出站报文（诊断/重放用）',
    resp_payload  LONGTEXT    COMMENT '供应商响应',
    status        VARCHAR(32) NOT NULL COMMENT 'INIT/MAPPING/SENDING/RETRYING/COMPENSATING/SUCCESS/DEAD_LETTER/UNKNOWN',
    attempt_count INT         NOT NULL DEFAULT 0 COMMENT '累计尝试次数（首送 + 短重试 + 补偿重试）',
    max_attempts  INT         NOT NULL DEFAULT 5 COMMENT '补偿最大次数（超限转死信；短重试上限另取 interface.max_retries）',
    next_retry_at DATETIME    COMMENT '下次重试/补偿时间（worker 扫描依据）',
    error_code    VARCHAR(32) COMMENT '错误码（50201 上游 5xx / 50401 超时等）',
    trace_id      VARCHAR(32) COMMENT '全链路 traceId',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_outreq_scan (status, next_retry_at),
    KEY idx_outreq_interface (interface_id),
    KEY idx_outreq_biz (app_id, biz_id),
    KEY idx_outreq_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出站请求（Flow A 状态机载体）';

-- 12 入站送达（Flow B 状态机载体）：ack 为回执（收到即回、与送达解耦）；重送按 callback_url_snapshot
CREATE TABLE inbound_delivery (
    id                    BIGINT       AUTO_INCREMENT PRIMARY KEY,
    interface_id          BIGINT       NOT NULL COMMENT '所属入站回调接口',
    app_id                VARCHAR(32)  NOT NULL COMMENT '供应商应用（冗余自 interface，便于按应用统计/扫描）',
    callback_event_id     VARCHAR(64)  COMMENT '供应商回调事件标识（审计/追溯）',
    payload               LONGTEXT     COMMENT '送达报文（出站侧 = 回调字段经字段映射后的结果）',
    callback_url_snapshot VARCHAR(255) COMMENT '送达回调地址快照（重送按快照地址执行，不随接口改址漂移）',
    delivery_status       VARCHAR(16)  NOT NULL COMMENT 'RECEIVED / ACKED（送达成功）/ PENDING（待重送）/ DEAD_LETTER（重送耗尽）',
    attempt_count         INT          NOT NULL DEFAULT 0 COMMENT '已尝试送达次数',
    max_attempts          INT          NOT NULL DEFAULT 5 COMMENT '重送最大次数（超限转死信）',
    next_retry_at         DATETIME     COMMENT '下次重送时间（补偿 worker 扫描依据）',
    ack_to_partner        VARCHAR(16)  COMMENT '回执状态（收到即回 ACKED，与送达结果解耦）',
    trace_id              VARCHAR(32)  COMMENT '全链路 traceId',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_delivery_scan (delivery_status, next_retry_at),
    KEY idx_delivery_interface (interface_id),
    KEY idx_delivery_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入站送达（Flow B 状态机载体）';

-- 13 死信：出站 4xx/重试耗尽/补偿耗尽、入站重送耗尽落此表；重放 = 重新入队
CREATE TABLE dead_letter (
    id         BIGINT        AUTO_INCREMENT PRIMARY KEY,
    biz_type   VARCHAR(16)   NOT NULL COMMENT 'OUTBOUND / INBOUND',
    ref_id     BIGINT        COMMENT '关联 outbound_request.id 或 inbound_delivery.id（多态引用，不设外键）',
    reason     VARCHAR(2000) COMMENT '死因（业务错误码/重试耗尽说明）',
    payload    LONGTEXT      COMMENT '报文快照（重放依据）',
    status     VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待处理 / HANDLED 已处理（重放后置位）',
    handled_at DATETIME      COMMENT '处理时间',
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_dead (biz_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='死信';

-- 14 调用日志：AOP 拦截记录；敏感字段落库前脱敏；高频追加表，按保留期归档/清理
CREATE TABLE call_log (
    id           BIGINT        AUTO_INCREMENT PRIMARY KEY,
    trace_id     VARCHAR(32)   COMMENT 'traceId（贯穿全链路）',
    span_id      VARCHAR(32)   COMMENT 'span 标识（OpenTelemetry）',
    direction    VARCHAR(8)    NOT NULL COMMENT 'IN 入站（调用方/供应商回调→平台）/ OUT 出站（平台→供应商/回调地址）',
    interface_id BIGINT        COMMENT '关联接口',
    app_id       VARCHAR(32)   COMMENT '关联应用（供应商）',
    url          VARCHAR(255)  COMMENT '目标 URL',
    method       VARCHAR(8)    COMMENT 'HTTP 方法',
    status_code  INT           COMMENT '状态码',
    latency_ms   BIGINT        COMMENT '耗时（毫秒）',
    req_headers  VARCHAR(2000) COMMENT '请求头（已脱敏）',
    req_body     LONGTEXT      COMMENT '请求体',
    resp_body    LONGTEXT      COMMENT '响应体',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    KEY idx_call_trace (trace_id),
    KEY idx_call_time (created_at),
    KEY idx_call_interface (interface_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用日志';

-- 15 告警规则：阈值告警（成功率下降/延迟超限/死信堆积/补偿失败）
CREATE TABLE alert_rule (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(64)  NOT NULL COMMENT '规则名',
    metric         VARCHAR(64)  NOT NULL COMMENT '指标：成功率 / 延迟 / 死信堆积 / 补偿失败',
    threshold      VARCHAR(128) NOT NULL COMMENT '阈值表达式',
    notify_channel VARCHAR(64)  COMMENT '通知渠道（邮件/IM）',
    enabled        TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '启用 / 停用',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_alert_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则';

-- 16 应用凭证：出站签名 / 回调验签两类；轮换并存由 ACTIVE + ROTATING 双窗口承载
-- （验签方向新旧并存、签名方向激活切换，详见 doc/开发文档/M0-04凭证轮换存储方案.md）
CREATE TABLE app_credential (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    app_id         VARCHAR(32)  NOT NULL COMMENT '所属应用',
    kind           VARCHAR(16)  NOT NULL COMMENT 'OUTBOUND 出站签名凭证 / CALLBACK 回调验签凭证',
    credential     TEXT         NOT NULL COMMENT '凭证内容（AES-256-GCM 可逆加密存储；复合凭证 JSON 化，如 {"secretId":..,"secretKey":..}）',
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 当前使用 / ROTATING 轮换并存 / RETIRED 已失效',
    activated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效时间',
    retired_at     DATETIME     COMMENT '失效时间（即时失效或窗口结束）',
    rotating_until DATETIME     COMMENT 'ROTATING 并存窗口截止（默认 +24h；过期后读取路径惰性视为 RETIRED）',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_credential_app (app_id, kind, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用凭证（出站签名 / 回调验签，支持轮换并存）';

-- ============================================================
-- 删除策略约定（不设数据库外键，引用完整性由应用层保证）
--   · 删适配器     → app.auth_adapter_id / callback_auth_adapter_id /
--                    default_message_adapter_id、binding.adapter_id 置 NULL
--                    （回退「无鉴权 / 平台默认」）
--   · 删应用       → 级联删其分组与凭证（app_credential）；存在接口时禁止删除（接口以「下线」为主）
--   · 删接口       → 级联删 6 张配置子表（snapshot/param/body/mapping/field_def/binding）；
--                    存在运行数据（outbound_request / inbound_delivery）时仅允许下线
--   · 删接口       → call_log.interface_id 置 NULL（日志保留，可观测数据不丢）
--   · dead_letter.ref_id 为多态引用（指向 outbound_request.id 或 inbound_delivery.id），不约束
-- ============================================================
