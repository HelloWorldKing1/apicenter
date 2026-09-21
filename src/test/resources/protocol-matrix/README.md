# 协议组合矩阵真实用例（真实公网供应商）

本目录是《协议组合矩阵接口测试报告.xlsx》（`src/main/resources/doc/开发文档/`）的**可复跑脚本与原始结果**。

| 文件 | 说明 |
|---|---|
| `run_protocol_matrix.py` | 执行脚本（仅标准库）：经管理面 API 建 5 个接口并发布，再以真实 HTTP 调用平台对外路径，最后回收「调用日志 + 出站状态机」证据 |
| `build_report.py` | 报告生成脚本：读取执行结果 + 拉取接口定义与出站状态机（**只读，不产生业务调用**），生成 Excel 报告与 Markdown 接口文档 |
| `protocol-matrix-results-20260921.json` | 2026-09-21 执行的**原始结果**（含实录报文与调用日志，Authorization 已被平台脱敏） |

产物：

| 产物 | 说明 |
|---|---|
| `src/main/resources/doc/开发文档/协议组合矩阵接口测试报告.xlsx` | 10 页签：说明 / 用例总览 / **接口参数** / **格式转换步骤** / 报文明细 / 映射与白名单 / 调用日志取证 / 状态机与状态链 / 发现与建议 / 清理 |
| `src/main/resources/doc/开发文档/协议组合矩阵接口文档.md` | 接口文档：接口定义 + 完整参数（入站/出站） + 12 步格式转换步骤（含实录报文） + 边界与复跑说明 |

## 用例矩阵

| 用例 | 组合 | 入站协议 | 出站协议 | 供应商（真实） | 方法 | 结果 |
|---|---|---|---|---|---|---|
| PM-T1-JJ | JSON→JSON | JSON | JSON | EVOLink `/v1/images/generations` | POST | ✅ 200 code=0（真实生图任务，计费 6.17 credits） |
| PM-T2-JX | JSON→XML | JSON | XML | USGS 地震 Atom Feed | GET | ✅ 200 code=0（RESP 白名单 7→3） |
| PM-T3-XX | XML→XML | XML | XML | USGS 地震 Atom Feed | GET | ✅ 200 code=0（entry 数组保形） |
| PM-T4-XJ | XML→JSON | XML | JSON | EVOLink `/v1/images/generations` | POST | ✅ 链路全通；供应商 404 model_not_found → 4xx 死信（错误路径，未计费） |
| PM-A1-XT | JSON→XML（辅助） | JSON | XML | httpbin `/post` | POST | ✅ 上游回显 XML 原文，证明出站报文真实发送 |

## 前置条件

1. 平台实例可访问（脚本默认 `http://localhost:8081`，可按需改 `ADMIN` / `GATEWAY`）；
2. 开发库已有应用 `EVOLINK`（含 ACTIVE 出站凭证）、`USGSXML`、`BRIEF`（base_url=httpbin）；
3. 实例需豁免管理面鉴权（脚本未带令牌）：以 `--app.api-center.auth.enabled=false` 启动，或改脚本为带
   `Authorization: Bearer <token>` 调用管理面。

## 复跑

```bash
# 1) 启动一个测试实例（同一开发库；关掉 worker 首跑避免与用例抢跑）
mvn -o spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 \
  --app.api-center.auth.enabled=false \
  --app.api-center.retry-worker-initial-delay-ms=3600000 \
  --app.api-center.alert-worker-initial-delay-ms=3600000"

# 2) 执行（接口已存在时会因 code/path 唯一冲突而跳过创建；如需重建先按报告「9-清理」删除）
python3 run_protocol_matrix.py

# 3) 重新生成报告与接口文档（只读，不会产生费用）
python3 build_report.py
```

## 注意

- **T1 会真实计费**（EVOLink 生图）；用例已设 `maxRetries=0`，保证每个用例至多 1 次上游调用。
- 脚本创建的资产均以 `PM-` 前缀命名，清理方式见报告的「7-清理」页。
- 真实供应商（公网）不适合进常规回归；建议按报告 F-7 的建议把本次实录报文固化为 WireMock 桩，
  新增 `ProtocolMatrixIT` 覆盖四组合。
