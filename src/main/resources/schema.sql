-- ============================================================
-- apicenter · API 三方接口统一调用平台组件 —— H2 Demo 建表脚本
-- 与设计文档 §9 数据模型对齐；生产迁移 MySQL 8.0 InnoDB 时仅需微调类型
-- application.yaml: spring.sql.init.mode=always 启动自动执行
-- ============================================================

-- 渠道配置（对齐 application.yaml app.integration.channels）
CREATE TABLE IF NOT EXISTS integration_channel (
    channel_code      VARCHAR(32)  PRIMARY KEY,
    base_url          VARCHAR(255) NOT NULL,
    auth_token        VARCHAR(255),
    signature_secret  VARCHAR(255),
    read_timeout_ms   INT          NOT NULL DEFAULT 3000,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE
);

-- 出站请求主记录（outbox），status 即状态机载体（设计文档 §6.1）
CREATE TABLE IF NOT EXISTS integration_request (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    biz_id           VARCHAR(64)  NOT NULL,
    biz_type         VARCHAR(32)  NOT NULL,
    channel_code     VARCHAR(32)  NOT NULL,
    endpoint         VARCHAR(255) NOT NULL,
    request_payload  CLOB,
    response_payload CLOB,
    status           VARCHAR(32)  NOT NULL,
    attempt_count    INT          NOT NULL DEFAULT 0,
    max_attempts     INT          NOT NULL DEFAULT 5,
    next_retry_at    TIMESTAMP,
    trace_id         VARCHAR(32),
    error_code       VARCHAR(32),
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 调用日志（AOP 写入，req_headers 已脱敏，设计文档 §7）
CREATE TABLE IF NOT EXISTS integration_call_log (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    trace_id     VARCHAR(32),
    span_id      VARCHAR(32),
    direction    VARCHAR(8)   NOT NULL,   -- OUT / IN
    channel_code VARCHAR(32),
    url          VARCHAR(255),
    method       VARCHAR(8),
    status_code  INT,
    latency_ms   BIGINT,
    req_headers  VARCHAR(2000),
    req_body     CLOB,
    resp_body    CLOB,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 死信（400 业务错误落这里，人工/管理端点处理，设计文档 §6.5）
CREATE TABLE IF NOT EXISTS dead_letter (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    request_id   BIGINT       NOT NULL,
    channel_code VARCHAR(32),
    biz_id       VARCHAR(64),
    reason       VARCHAR(2000),
    payload      CLOB,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING / HANDLED
    handled_at   TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Flow B 入站回调订阅配置（ERP 预配置，设计文档 §4.3）
CREATE TABLE IF NOT EXISTS callback_subscription (
    id                  BIGINT        AUTO_INCREMENT PRIMARY KEY,
    channel_code        VARCHAR(32)   NOT NULL,
    callback_path       VARCHAR(255)  NOT NULL,
    map_rule_id         BIGINT,
    erp_callback_url    VARCHAR(255)  NOT NULL,
    erp_auth_token      VARCHAR(255),
    erp_signature_secret VARCHAR(255),
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE
);

-- 送达 / 补偿记录（Flow B，设计文档 §6.2）
CREATE TABLE IF NOT EXISTS callback_delivery (
    id                  BIGINT        AUTO_INCREMENT PRIMARY KEY,
    subscription_id     BIGINT        NOT NULL,
    callback_event_id   VARCHAR(64),
    payload             CLOB,
    delivery_status     VARCHAR(32)   NOT NULL,  -- RECEIVED / ERP_ACKED / PENDING / RETRYING
    attempt_count       INT           NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMP,
    ack_to_partner_status VARCHAR(16),
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 高水位游标（设计文档 §8.1）
CREATE TABLE IF NOT EXISTS sync_watermark (
    sync_job        VARCHAR(64)  NOT NULL,
    channel_code    VARCHAR(32)  NOT NULL,
    watermark_value VARCHAR(64)  NOT NULL,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (sync_job, channel_code)
);

-- 定时拉取执行审计（设计文档 §8.1）
CREATE TABLE IF NOT EXISTS sync_job_log (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    job_name        VARCHAR(64)  NOT NULL,
    channel_code    VARCHAR(32),
    start_watermark VARCHAR(64),
    end_watermark   VARCHAR(64),
    pulled_count    INT          NOT NULL DEFAULT 0,
    dedup_count     INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL,
    run_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 同窗口防重（唯一键 biz_type + channel_code + biz_id，设计文档 §8.2）
CREATE TABLE IF NOT EXISTS idempotency_key (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    biz_type     VARCHAR(32)  NOT NULL,
    channel_code VARCHAR(32)  NOT NULL,
    biz_id       VARCHAR(64)  NOT NULL,
    expire_at    TIMESTAMP,
    CONSTRAINT uk_idem UNIQUE (biz_type, channel_code, biz_id)
);
