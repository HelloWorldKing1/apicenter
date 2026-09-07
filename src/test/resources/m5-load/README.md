# M5 压测脚本（D-M5-3）

> 工具：wrk（`brew install wrk`；备选 k6 / hey——环境差异不阻塞，仅换工具不换场景）。
> ⚠ 造数记录会被补偿 worker **真实重放**——必须指向本地 WireMock 上游接口，严禁指向真实供应商。

| 文件 | 用途 |
|---|---|
| `wrk-envelope.lua` | 场景 1 出站链路吞吐：POST JSON 黄金用例同构请求 |
| `wrk-callback.lua` | 场景 5 同步送达压测：POST 平台入站回调端点 |
| `seed-compensating.sql` | 场景 3 造数：1 万条 COMPENSATING（接口必须指向本地 WireMock 上游，跑后清理） |
| `clean-compensating.sql` | 场景 3 清理：按 biz_id 前缀 / 时间窗删造数记录 |

## 场景命令

```bash
# 场景 1：出站吞吐（30s × 100 并发，上游 WireMock 200 直通）
wrk -t4 -c100 -d30s -s src/test/resources/m5-load/wrk-envelope.lua \
  --header "Content-Type: application/json" http://localhost:8080/<平台路径>

# 场景 2：QPS 限流阈值生效（app.qps_limit=50，200 并发）
wrk -t4 -c200 -d30s -s src/test/resources/m5-load/wrk-envelope.lua \
  http://localhost:8080/<限流应用平台路径>
# 观察：通过量 ≈50/s、其余 42901、运行表无污染（M4 能力验证）

# 场景 3：补偿批量扫描（先造数 1 万条 → 观察 worker 消化总时长）
mysql ... apicenter < src/test/resources/m5-load/seed-compensating.sql
# 造数接口必须指向本地 WireMock；跑后清理：
mysql ... apicenter < src/test/resources/m5-load/clean-compensating.sql

# 场景 4：场景 1 压测期间观察 call_log 零丢弃
curl -s http://localhost:8080/actuator/prometheus | grep apicenter_calllog_dropped

# 场景 5：同步送达（入站回调端点，本地 stub 送达地址）
wrk -t4 -c100 -d30s -s src/test/resources/m5-load/wrk-callback.lua http://localhost:8080/<入站回调路径>
```

## 环境注意
- 开发库为远程 PolarDB（每 SQL WAN 往返 ~百毫秒）：绝对值为流程验证 + 相对基线，
  **绝对值不作 KPI**（M4 实测 IN ~1s / OUT ~9ms 即读放大所致）；贴近生产需本地库复测。
- 压测结论写入 `doc/开发文档/M5压测报告.md`（环境 / 脚本 / 数据 / 结论 / 调优清单 / 同步送达决策）。
