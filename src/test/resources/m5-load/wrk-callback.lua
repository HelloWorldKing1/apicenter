-- M5 场景 5：入站回调同步送达（供应商 → 平台；送达地址为本地 WireMock stub）
-- 需按接口 CALLBACK 凭证签名的 X-Timestamp / X-Partner-Signature（HMAC-SHA256）：
-- 演示环境可临时选用不验签（Noop）回调接口简化压测脚本（D-M5-3 场景五产出 = 送达/ack 时序结论）
wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"
wrk.headers["X-Timestamp"] = "0"
wrk.headers["X-Partner-Signature"] = "bypass-demo"
wrk.body = '{"event_id":"evt-x","order_id":"O-1","state":"PAID"}'
