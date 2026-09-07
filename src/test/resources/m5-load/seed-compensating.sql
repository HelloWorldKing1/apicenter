-- M5 场景 3 造数：1 万条 COMPENSATING（接口必须指向本地 WireMock 上游，严禁真实供应商！）
-- 按时间窗生成（next_retry_at 摊开防同一轮全量命中），biz_id 带 M5LOAD- 前缀便于清理。
INSERT INTO outbound_request (interface_id, app_id, biz_id, in_payload, out_payload, resp_payload,
                              status, attempt_count, max_attempts, next_retry_at, error_code, trace_id)
SELECT i.id, a.app_id, CONCAT('M5LOAD-', LPAD(n, 8, '0')),
       '{}', NULL, NULL, 'COMPENSATING', 0, 5,
       DATE_ADD(NOW(), INTERVAL MOD(n, 60) MINUTE), '50201', CONCAT('load-', n)
FROM interface i
JOIN app a ON a.app_id = i.app_id
CROSS JOIN (
    SELECT (a.n + b.n * 10 + c.n * 100 + d.n * 1000) + 1 AS n
    FROM (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5
          UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
    CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5
          UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
    CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5
          UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
    CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5
          UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
) seq
WHERE i.id = (SELECT id FROM interface WHERE path = '<本地 WireMock 接口平台路径>')
LIMIT 10000;
