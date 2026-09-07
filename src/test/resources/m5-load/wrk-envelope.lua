-- M5 场景 1/2：POST JSON（黄金用例同构请求体，覆盖链执行 + 状态机写路径）
wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"
wrk.body = '{"filter":{"seller_id":"7494312521977267257"},"orderby":[{"field":"units_sold","order":"desc"}],"page":1,"pagesize":1}'
