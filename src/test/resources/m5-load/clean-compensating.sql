-- M5 场景 3 清理：删造数记录（M5LOAD- 前缀；含其级联 dead_letter 引用——重放成功 / 耗尽转死信的行一并清）
DELETE dl FROM dead_letter dl
  JOIN outbound_request o ON o.id = dl.ref_id AND dl.biz_type = 'OUTBOUND'
  WHERE o.biz_id LIKE 'M5LOAD-%';
DELETE FROM outbound_request WHERE biz_id LIKE 'M5LOAD-%';
