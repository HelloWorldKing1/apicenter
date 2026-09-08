-- ============================================================================
-- reset-dev.sql —— 开发库一键重置脚本（配合《整体测试方案》§10.4）
-- ⚠ 仅限开发/测试库（apicenter），严禁在生产执行。执行前请先确认：
--      SELECT DATABASE();  -- 必须返回 apicenter
--    并停掉 dev 后端（8080）与 WireMock，避免与在途请求/worker 并发。
-- 默认采用 TRUNCATE：清空数据并把自增 ID 重置为从 1 开始。
-- adapter 表【保留不重置】（自建/seed 适配器实例保留；如要连它一起重置见附录 B）。
-- 用法：
--   场景一（日常每轮测试）：只执行「一、运行数据」；
--   场景二（重头开始）：再执行「二、配置数据」；
--     完成后重启后端（空库启动不再自动导入）；需要 fastmoss 演示基线时
--     执行 POST /api/admin/seed/import 手动导入（幂等，重建应用/凭证/接口）。
-- 注意：TRUNCATE 每句隐式提交（中途失败=部分完成，请分段执行）；
--       alert_rule 为自建规则 seed 不重建；app_credential 密文删除不可恢复。
-- ============================================================================

-- ============================================================================
-- 一、运行数据（6 张，TRUNCATE 自增重置）
-- ============================================================================
TRUNCATE TABLE dead_letter;          -- ref_id 多态引用运行表
TRUNCATE TABLE reconcile_audit;      -- 对账审计（关联 outbound_request）
TRUNCATE TABLE outbound_request;     -- 出站状态机载体
TRUNCATE TABLE inbound_delivery;     -- 入站送达
TRUNCATE TABLE call_log;             -- 调用日志
TRUNCATE TABLE alert_event;          -- 告警触发事件

-- ============================================================================
-- 二、配置数据（11 张，adapter 保留；重头开始才执行）
-- ============================================================================
TRUNCATE TABLE interface_param;
TRUNCATE TABLE interface_body;
TRUNCATE TABLE interface_field_mapping;
TRUNCATE TABLE interface_field_def;
TRUNCATE TABLE interface_adapter_binding;
TRUNCATE TABLE interface_snapshot;   -- M5 版本快照
TRUNCATE TABLE interface;
TRUNCATE TABLE app_group;
TRUNCATE TABLE app_credential;       -- 凭证密文（seed 会重建种子值）
TRUNCATE TABLE app;
TRUNCATE TABLE alert_rule;           -- 自建告警规则（seed 不重建）

-- ============================================================================
-- 附录 A：备选——不想重置自增 ID 时，用 DELETE 等价格式替代以上 TRUNCATE：
-- ============================================================================
-- DELETE FROM dead_letter;
-- DELETE FROM reconcile_audit;
-- DELETE FROM outbound_request;
-- DELETE FROM inbound_delivery;
-- DELETE FROM call_log;
-- DELETE FROM alert_event;
--
-- DELETE FROM interface_param;
-- DELETE FROM interface_body;
-- DELETE FROM interface_field_mapping;
-- DELETE FROM interface_field_def;
-- DELETE FROM interface_adapter_binding;
-- DELETE FROM interface_snapshot;
-- DELETE FROM interface;
-- DELETE FROM app_group;
-- DELETE FROM app_credential;
-- DELETE FROM app;
-- DELETE FROM alert_rule;

-- ============================================================================
-- 附录 B：可选——如需连自建适配器一起清空并重置 ID（验证 seed 全量导入）：
-- ============================================================================
-- TRUNCATE TABLE adapter;
-- （放在「二、配置数据」之前执行即可）
-- ============================================================================
