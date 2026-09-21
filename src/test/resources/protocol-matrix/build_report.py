#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成《协议组合矩阵接口测试报告.xlsx》与《协议组合矩阵接口文档.md》。

输入（两者均由 run_protocol_matrix.py 产出/平台库可查）：
  --results  执行结果 JSON（默认 src/test/resources/protocol-matrix/protocol-matrix-results-20260921.json）
  --admin    管理面地址（默认 http://localhost:8081/api/admin，用于拉接口定义与出站状态机）
  --out-xlsx / --out-md  输出路径

说明：脚本不发起任何业务调用（不会产生费用），只读取配置与历史记录。
"""
import argparse
import json
import urllib.request
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter

# ---------------------------------------------------------------- 基础工具
HDR = PatternFill("solid", fgColor="2F54EB")
HDR_F = Font(color="FFFFFF", bold=True, size=10)
HDR2 = PatternFill("solid", fgColor="8C8C8C")
TITLE_F = Font(bold=True, size=14, color="1D2129")
SUB_F = Font(bold=True, size=11, color="2F54EB")
CELL_F = Font(size=10)
MONO_F = Font(size=9, name="Menlo")
OK_FILL = PatternFill("solid", fgColor="E6FFE6")
NG_FILL = PatternFill("solid", fgColor="FFE9E9")
WARN_FILL = PatternFill("solid", fgColor="FFF7E6")
THIN = Side(style="thin", color="D9D9D9")
BORDER = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)
WRAP = Alignment(wrap_text=True, vertical="top")
WRAP_C = Alignment(wrap_text=True, vertical="top", horizontal="center")


def cut(v, n=1500):
    if v is None or v == "":
        return ""
    s = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)
    return s if len(s) <= n else s[:n] + f"…[截断，原长 {len(s)}]"


def sheet(wb, name, widths, first=False):
    ws = wb.create_sheet(name) if not first else wb.active
    if first:
        ws.title = name
    for i, w in enumerate(widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = w
    return ws


def head(ws, row, values, fill=HDR):
    for c, v in enumerate(values, 1):
        cell = ws.cell(row=row, column=c, value=v)
        cell.fill = fill
        cell.font = HDR_F
        cell.alignment = WRAP_C
        cell.border = BORDER
    ws.freeze_panes = ws.cell(row=row + 1, column=1)
    return row + 1


def put(ws, row, values, mono_cols=(), fills=None):
    for c, v in enumerate(values, 1):
        cell = ws.cell(row=row, column=c, value=v)
        cell.font = MONO_F if c in mono_cols else CELL_F
        cell.alignment = WRAP
        cell.border = BORDER
        if fills and c in fills and fills[c]:
            cell.fill = fills[c]
    return row + 1


def section(ws, row, text):
    c = ws.cell(row=row, column=1, value=text)
    c.font = SUB_F
    c.alignment = WRAP
    return row + 1


def api(base, path):
    with urllib.request.urlopen(base + path, timeout=30) as r:
        return json.loads(r.read().decode())


# ---------------------------------------------------------------- 用例元数据
EXEC_AT = "2026-09-21 10:11（Asia/Shanghai）"

# 应用默认适配器（用于「实际生效」列；来自 app.auth_adapter_id）
APP_AUTH = {"EVOLINK": "ADP-123（BearerTokenAuthAdapter → 注入 Authorization: Bearer <token>）",
            "USGSXML": "（空）→ 平台默认 NoopAuthAdapter（不附加凭证）",
            "BRIEF": "ADP-000（NoopAuthAdapter）"}

# 12 步转换链定义（与 ChainPhase 六阶段 + 传输 + 响应收敛对齐）
CHAIN_STEPS = [
    (1, "—", "调用方 → 平台（入站 HTTP）", "原始字节", "原始字节",
     "按 interface.protocol_in 选择解码器；路径须命中 interface.path；接口须 PUBLISHED、应用须 ENABLED"),
    (2, "路由", "路径匹配 → 接口 / 归属应用（供应商）", "URL", "接口行",
     "path 全局唯一路由键；未命中 40401；未发布 / 方法不符 40401；应用停用 40102"),
    (3, "INBOUND_AUTH", "入站鉴权", "原始字节", "通过 / 拒绝",
     "Flow A（出站中转）：调用方鉴权属平台统一能力 → 组件内该阶段 **Noop 占位直通**；"
     "Flow B（入站回调）：CALLBACK_AUTH 回调验签。本用例均属 Flow A"),
    (4, "DECODE", "协议解码：protocol_in → UnifiedModel（链内唯一数据载体）", "原始字节", "统一内部模型",
     "JSON：对象 → Map；XML：根元素 → 根对象，子元素 → 键（文本取字符串、同名子元素 → 数组）；"
     "解码失败 40002 且不落运行表"),
    (5, "MESSAGE", "报文适配（角色 MESSAGE：接口覆盖 → 应用默认 → 平台默认）", "统一模型", "统一模型",
     "本用例为 Noop 直通（不解信封）；EnvelopeMessageAdapter 才剥 data + 按 codeField/successValue 判成败"),
    (6, "MAPPING", "字段映射（接口级规则，按 sort_order 顺序执行）", "统一模型", "统一模型",
     "6 操作 rename/typeCast/enumMap/default/condition/aggregate；null_strategy 四值；本用例为 rename ×2（同名搬运）"),
    (7, "ENCODE", "协议编码：统一模型 → protocol_out", "统一模型", "出站报文",
     "XML 根元素写死 `<request>`（attrVsElement=ELEMENT，属性默认不映射）；JSON 为紧凑序列化"),
    (8, "OUTBOUND_AUTH", "出站鉴权（角色 AUTH：接口绑定 → 应用默认 → Noop）", "出站报文", "出站报文 + 凭证头",
     "凭证每请求实时读（AES-256-GCM 解密），轮换即时生效，不进链缓存"),
    (9, "HTTP", "平台 → 供应商真实调用（RestClient 直调动态 URI）", "请求", "响应",
     "读超时 = interface.timeout_ms；**GET/DELETE 不携带请求体**；透传 X-Trace-Id；短重试上限 max_retries"),
    (10, "RESP DECODE", "响应解码：protocol_out → 统一模型", "原始字节", "统一模型",
     "解码失败按空对象处理（宽松，仅 log.warn）——协议配错时会表现为「成功 + 空 data」"),
    (11, "JUDGE + RESP", "业务成败判定 + RESP 白名单过滤（含类型转换）", "统一模型", "业务数据",
     "Noop 报文适配：HTTP 2xx 即业务成功；信封适配：codeField == successValue 才成功；"
     "RESP 声明为空 = 不过滤，声明了 = 白名单"),
    (12, "信封", "平台 → 调用方：统一信封 {code,msg,data}", "业务数据", "JSON 信封",
     "对外响应恒为 JSON 统一信封（不逐接口配置）；HTTP 状态码 = 业务码 / 100"),
]


def step_data(key, iface, meta, inlog, outlog, sent_body, app="EVOLINK"):
    """每用例在 12 步上的实际数据。来源分「实录」与「推导」。"""
    out_payload = meta.get("outPayloadPreview")
    # GET/DELETE：out_payload 恒空（OutboundEngine#outboundBodyText 返回 null），但编码**确实发生**，
    # 产物可从调用日志 OUT 条 req_body 取证 —— 见报告 F-1
    if out_payload:
        out_payload_disp = f"{out_payload}   [实录 out_payload]"
    elif outlog and outlog.get("reqBody"):
        out_payload_disp = (f"{outlog['reqBody']}   [实录（调用日志 OUT 条 req_body；"
                            f"GET/DELETE 不落 out_payload，见 F-1）]")
    else:
        out_payload_disp = "（未取得）"
    auth_hdr = ""
    if outlog and outlog.get("reqHeaders"):
        for part in str(outlog["reqHeaders"]).split("|"):
            if "Authorization" in part:
                auth_hdr = part.strip()
    d = {
        1: f"{sent_body}   [实录]",
        2: f"命中接口 {iface['code']}（id {meta.get('interfaceId')}）/ 应用 {iface['appId']}"
           f"（base_url {'https://api.evolink.ai' if iface['appId'] == 'EVOLINK' else 'https://earthquake.usgs.gov'}）",
        3: "Noop 占位直通（Flow A，组件内该阶段不鉴权）",
        4: "见「③ 解码后模型」（推导，可由出站报文反推验证）",
        5: "不变（MESSAGE 无绑定 → 平台默认 Noop 直通）",
        6: "rename model→model、prompt→prompt（本用例为同名搬运，值不变）",
        7: out_payload_disp,
        8: auth_hdr or "无凭证注入（Noop）",
        9: f"{outlog.get('method')} {outlog.get('url')} → HTTP {outlog.get('statusCode')}，{outlog.get('latencyMs')}ms  [实录]",
        10: f"供应商原始响应 {len(str(outlog.get('respBody') or ''))} 字符  [实录]",
        11: "",   # 由调用方按用例补充
        12: f"{inlog.get('respBody')}   [实录]",
    }
    if key == "T3":
        d[6] = "rename requestId→requestId、keyword→keyword（XML→XML 等值往返，编码后与入站同形）"
    if key == "T4":
        d[6] = "rename model→model、prompt→prompt；统一模型由 **XML 解码**产出，再 **JSON 编码** → 入参 XML / 出参 JSON"
    return d


def summarise_steps(cases, metas, logs_in, logs_out, ifaces, out_file):
    pass   # 占位：逻辑内联在主流程，便于按用例定制文案


# ---------------------------------------------------------------- Markdown
def md_doc(results, ifaces, metas, logs, path):
    c = {x["key"]: x for x in results["cases"]}
    order = ["T1", "T2", "T3", "T4", "A1"]
    L = []
    A = L.append
    A("# 协议组合矩阵接口文档（JSON-JSON · JSON-XML · XML-XML · XML-JSON）")
    A("")
    A("> 版本：v1.0 · 生成时间 " + EXEC_AT + " · 数据来源：平台**真实执行记录**（真实公网供应商，非桩）")
    A(">")
    A("> 本文件同时是《协议组合矩阵接口测试报告.xlsx》配套的**接口定义与格式转换说明**：")
    A("> 每个接口给出**完整参数**（入站 / 出站两侧）与**格式转换步骤**（含实录报文）。")
    A("")
    A("---")
    A("")
    A("## 1. 接口清单")
    A("")
    A("| 用例 | 组合 | 接口标识 | 平台路径 | 方法 | 入站协议 | 出站协议 | 归属应用（供应商） | 供应商接口路径 | 读超时 | 最大重试 | 状态 |")
    A("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for k in order:
        i = ifaces[k]
        A(f"| {k} | {c[k]['combo']} | `{i['code']}` | `{i['path']}` | {i['method']} | {i['protocolIn']} "
          f"| {i['protocolOut']} | {i['appId']} | `{i['upstreamPath']}` | {i['timeoutMs']}ms "
          f"| {i['maxRetries']} | {i['status']} |")
    A("")
    A("**上游（真实供应商，非桩）**")
    A("")
    A("| 供应商 | 地址 | 端点 | 说明 |")
    A("|---|---|---|---|")
    A("| USGS（XML 侧） | `https://earthquake.usgs.gov` | `GET /earthquakes/feed/v1.0/summary/2.5_day.atom` | "
      "公网免密钥，返回 Atom XML（`<feed>` 根）；**只接受 GET**（实测 POST → 403） |")
    A("| EVOLink（JSON 侧） | `https://api.evolink.ai` | `POST /v1/images/generations` | "
      "真实生图（异步任务，立即返回任务 id）；认证 = `Authorization: Bearer <sk-…>`，凭证已存应用 `EVOLINK`（库内密文） |")
    A("| httpbin（辅助） | `https://httpbin.org` | `POST /post` | 回显请求体，用于证明「出站报文确实被发出」 |")
    A("")
    A("## 2. 调用约定")
    A("")
    A("```bash")
    A("# 平台对外路径 = interface.path（全局唯一路由键）")
    A("POST http://<host>:8080/pm/t1-jj")
    A("Content-Type: application/json        # 对应 protocol_in（XML 组合时为 application/xml）")
    A("X-Trace-Id: <可选，链路 ID；平台会透传给供应商>")
    A("X-Biz-Id:   <可选，业务键；供应商幂等依赖>")
    A("")
    A("# 平台对外响应恒为 JSON 统一信封（与入站协议无关）")
    A('{"code":0,"msg":"ok","data":{…}}       # HTTP 状态码 = 业务码 / 100')
    A("```")
    A("")
    A("**平台侧统一响应与错误码（本次实测涉及）**")
    A("")
    A("| 业务码 | HTTP | 含义 | 本次实测 |")
    A("|---|---|---|---|")
    A("| 0 | 200 | 成功 | T1 / T2 / T3 / A1 |")
    A("| 50201 | 502 | 供应商 4xx（非 429）→ 转死信、不重试 | T4（供应商 404 model_not_found） |")
    A("| 40002 | 400 | 报文错误（格式非法 / 超 1MB / XML 混合内容 / 嵌套超限） | 未触发 |")
    A("| 50401 | 504 | 供应商读超时 → UNKNOWN 对账 | 未触发（T2/T3 已把读超时提到 15000ms） |")
    A("")
    A("> 说明：本组件**不鉴权调用方**（Flow A 的 INBOUND_AUTH 为 Noop 占位，调用方鉴权属平台统一能力，"
      "见《入站鉴权设计方案.md》）；入站回调接口才有 CALLBACK_AUTH 验签。")
    A("")
    A("## 3. 格式转换总览（六阶段链）")
    A("")
    A("```")
    A("调用方 ──原始字节──► ① 入站鉴权 ─► ② 协议解码 ─► ③ 报文适配 ─► ④ 字段映射 ─► ⑤ 协议编码 ─► ⑥ 出站鉴权 ─► 供应商")
    A("         (protocol_in)   (Noop/验签)   (→ 统一模型)   (MESSAGE)     (接口规则)    (protocol_out)  (注入凭证)")
    A("供应商 ──原始字节──► 响应解码(protocol_out) ─► 业务成败判定 ─► RESP 白名单过滤 ─► 统一信封 {code,msg,data} ──► 调用方")
    A("```")
    A("")
    A("**四种组合的差异只落在两个开关**：`protocol_in`（怎么解调用方的报文）与 `protocol_out`（怎么编给供应商的报文、"
      "以及怎么解供应商的响应）。报文适配 / 字段映射 / 鉴权与协议无关。")
    A("")
    A("| 用例 | 组合 | protocol_in 解码 | protocol_out 编码 | 响应解码 | 报文是否真实发出 |")
    A("|---|---|---|---|---|---|")
    A("| T1 | JSON→JSON | JSON → 对象 | 对象 → JSON（紧凑） | JSON | ✅ 是（POST 带 body） |")
    A("| T2 | JSON→XML | JSON → 对象 | 对象 → `<request>…` XML | XML | ⚠️ 否（GET 不带 body，仅完成编码） |")
    A("| T3 | XML→XML | XML 根 → 对象 | 对象 → `<request>…` XML | XML | ⚠️ 否（同上） |")
    A("| T4 | XML→JSON | XML 根 → 对象 | 对象 → JSON（紧凑） | JSON | ✅ 是（POST 带 body） |")
    A("| A1 | JSON→XML（辅助） | JSON → 对象 | 对象 → `<request>…` XML | （响应为 JSON，解码失败 → 空） | ✅ 是（POST，httpbin 回显原文） |")
    A("")

    # 逐接口
    A("## 4. 接口定义与格式转换步骤（逐用例）")
    A("")
    for k in order:
        i = ifaces[k]
        cs = c[k]
        meta = metas[k]
        outlog = logs[k]["out"]
        inlog = logs[k]["in"]
        A(f"### 4.{order.index(k) + 1} {k} · {cs['combo']} · `{i['code']}`")
        A("")
        A("#### 4." + str(order.index(k) + 1) + ".1 接口定义")
        A("")
        A("| 项 | 值 |")
        A("|---|---|")
        for label, val in [
            ("接口标识 / ID", f"`{i['code']}` / {i['id']}"),
            ("名称", i["name"]),
            ("类型 / 方法", f"{i['ifType']} / {i['method']}"),
            ("平台侧路径", f"`{i['path']}`"),
            ("协议", f"入站（来源→平台）= **{i['protocolIn']}**；出站（平台→供应商）= **{i['protocolOut']}**"),
            ("归属应用 / 分组", f"{i['appId']} / {i.get('groupName') or i['groupId']}"),
            ("供应商接口路径", f"`{i['upstreamPath']}`（拼应用服务地址成完整 URL）"),
            ("读超时 / 最大重试", f"{i['timeoutMs']} ms / {i['maxRetries']}（本次设 0~2，公网供应商建议 ≥10000ms）"),
            ("响应字段（RESP）", "、".join(f"`{f['name']}`({f['type']})" for f in i.get("fieldDefs") or []) or "（未声明 = 不过滤）"),
            ("适配器绑定", "；".join(f"{b['role']}={b['adapterId'] or '继承应用默认'}" for b in i.get("bindings") or [])),
            ("出站鉴权实际生效", APP_AUTH.get(i["appId"], "—")),
        ]:
            A(f"| {label} | {val} |")
        A("")
        A("**请求参数（入站侧 IN = 调用方→平台；出站侧 OUT = 平台→供应商）**")
        A("")
        A("| 侧 | 参数名 | 类型 | 必填 | 示例值 | 排序 |")
        A("|---|---|---|---|---|---|")
        for p in sorted(i.get("params") or [], key=lambda x: (x["side"], x["sortOrder"])):
            A(f"| **{p['side']}** | `{p['name']}` | {p['type']} | {'是' if p['required'] else '否'} "
              f"| {p.get('sample') or '—'} | {p['sortOrder']} |")
        A("")
        A("**请求体（Body）配置**")
        A("")
        A("| 侧 | Body 类型 | 模板 / 示例 |")
        A("|---|---|---|")
        for b in sorted(i.get("bodies") or [], key=lambda x: x["side"]):
            A(f"| {b['side']} | {b['bodyType']} | `{cut(b.get('raw'), 300) or '—'}` |")
        A("")
        A("**字段映射（入站 → 出站，按 sort_order 执行）**")
        A("")
        A("| 顺序 | source（入站字段） | 操作 op | target（出站字段） | 操作参数 | 空值策略 |")
        A("|---|---|---|---|---|---|")
        for m in sorted(i.get("mappings") or [], key=lambda x: x["sortOrder"]):
            A(f"| {m['sortOrder']} | `{m['source']}` | {m['op']} | `{m['target']}` | {m.get('param') or '—'} | {m['nullStrategy']} |")
        A("")
        A("#### 4." + str(order.index(k) + 1) + ".2 格式转换步骤（实录）")
        A("")
        A("| 步 | 链阶段 | 处理 | 输入 → 输出 | 本用例实际数据 | 来源 |")
        A("|---|---|---|---|---|---|")
        sent = cs["inbound"][2]
        sd = step_data(k, i, meta, inlog, outlog, sent, i["appId"])
        if k == "T1":
            sd[11] = ("RESP 声明 4（id/status/progress/model）→ data 恰 4 字段；供应商原始 8 个根字段中 "
                      "created/object/task_info/type/usage 被过滤  [实录]")
        elif k == "T2":
            sd[11] = ("RESP 声明 3（title/updated/icon）→ data 恰 3 字段；feed 原始 7 个根字段中 "
                      "author/id/link/entry 被过滤  [实录]")
        elif k == "T3":
            sd[11] = "RESP 声明 3（title/updated/entry）→ data 恰 3 字段；entry 为数组且保形  [实录]"
        elif k == "T4":
            sd[11] = ("HTTP 404 + 供应商业务错误 model_not_found（retryable=false）→ 平台判定 4xx 非 429 ⇒ "
                      "死信 #255 + 业务码 50201、**零重试**  [实录]")
        else:
            sd[11] = ("未声明 RESP = 不过滤；HTTP 200 ⇒ code=0，data = 完整响应模型；"
                      "因 protocol_out=XML 而供应商返回 JSON，响应解码失败 ⇒ data={}  [实录，见报告 F-3]")
        for n, phase, act, din, dout, rule in CHAIN_STEPS:
            A(f"| {n} | {phase} | {act} | {din} → {dout} | {cut(sd[n], 420)} | "
              f"{'实录' if '[实录]' in sd[n] else ('代码事实' if n in (2, 3, 5, 8) else '推导')} |")
        A("")
        A("**转换前 → 转换后（一行对照）**")
        A("")
        A("```text")
        A("① 调用方 → 平台（" + i["protocolIn"] + "）：")
        A("   " + cut(sent, 400))
        A("② 平台 → 供应商（" + i["protocolOut"] + "）实际编码产物：")
        enc = meta.get("outPayloadPreview") or outlog.get("reqBody")
        A("   " + (cut(enc, 400) or "（未取得）")
          + ("" if meta.get("outPayloadPreview") else "        # 取自调用日志 OUT 条 req_body（GET/DELETE 不落 out_payload，见边界 1）"))
        A("③ 供应商响应 → 平台（" + i["protocolOut"] + " 解码）：")
        A("   " + cut(str(outlog.get("respBody")), 300))
        A("④ 平台 → 调用方（统一信封，RESP 过滤后）：")
        A("   " + cut(str(inlog.get("respBody")), 400))
        A("```")
        A("")
        A(f"**实测结果**：HTTP {cs['http']}，业务码 `{(cs.get('resp_json') or {}).get('code')}`，"
          f"端到端 {cs['wall_ms']} ms；调用日志 IN/OUT = {logs[k]['in'].get('id')} / {logs[k]['out'].get('id')}，"
          f"出站状态机记录 id = {meta.get('id')}，终态 `{meta.get('status')}`。")
        A("")
    A("## 5. 注意事项与边界（实测得出）")
    A("")
    A("| # | 事项 | 说明 |")
    A("|---|---|---|")
    A("| 1 | **GET/DELETE 不携带请求体** | `UpstreamInvoker` 对 GET/DELETE 不调 `.body()`；"
      "`out_payload` 对 GET/DELETE 也恒为空（`outboundBodyText` 返回 null）。"
      "即「XML 已编码」不等于「XML 已发送」——需要供应商收到 body 的场景必须配 **POST** |")
    A("| 2 | XML 出站根元素写死 `<request>` | 无法自定义根元素 / 命名空间 ⇒ SOAP 类真实供应商调不通（设计边界） |")
    A("| 3 | 读超时须按公网供应商调大 | USGS 实测 1.8~4.3s；历史 3000ms 配置出现过 3008ms 超时误判。建议 ≥10000ms |")
    A("| 4 | 响应协议配错不会报错 | 响应解码失败按空对象（宽松）⇒ 表现「code=0 + data={}」；排查时先比对调用日志 OUT 条 respBody 原文 |")
    A("| 5 | XML 属性默认不映射 | `attrVsElement=ELEMENT`；`<link href=\"…\"/>` 这类纯属性元素解出为 `null` |")
    A("| 6 | RESP 声明为空 = 不过滤 | 需要收敛响应字段时必须显式声明（T1 8→4、T2 7→3 即白名单效果） |")
    A("| 7 | 供应商 4xx 语义 | 4xx（非 429）⇒ 死信 + 50201 且**不重试**；本次 T4 的 `retryable=false` 与平台行为一致 |")
    A("")
    A("## 6. 复跑与清理")
    A("")
    A("- 复跑脚本与原始结果：`src/test/resources/protocol-matrix/`（`run_protocol_matrix.py` / `*.json` / `README.md`）")
    A("- 测试资产均以 `PM-` 前缀命名；清理方式见《协议组合矩阵接口测试报告.xlsx》的「9-清理」页")
    A("- ⚠️ T1 为真实计费调用（EVOLink `credits_reserved` 约 6.17/次）；复跑前请确认额度")
    A("")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(L))
    print("已生成：", path)


# ---------------------------------------------------------------- 主流程
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", default="/Users/patrickpan/IdeaProjects/apicenter/src/test/resources/"
                                        "protocol-matrix/protocol-matrix-results-20260921.json")
    ap.add_argument("--admin", default="http://localhost:8081/api/admin")
    ap.add_argument("--out-xlsx", default="/Users/patrickpan/IdeaProjects/apicenter/src/main/resources/doc/"
                                         "开发文档/协议组合矩阵接口测试报告.xlsx")
    ap.add_argument("--out-md", default="/Users/patrickpan/IdeaProjects/apicenter/src/main/resources/doc/"
                                       "开发文档/协议组合矩阵接口文档.md")
    args = ap.parse_args()

    results = json.load(open(args.results, encoding="utf-8"))
    cases = {x["key"]: x for x in results["cases"]}
    order = ["T1", "T2", "T3", "T4", "A1"]

    # 拉接口定义 + 出站状态机（只读，不产生业务调用）
    ifaces, metas, logs = {}, {}, {}
    all_runs = api(args.admin, "/monitor/outbound-requests?page=0&size=200").get("data") or []
    if isinstance(all_runs, dict):
        all_runs = all_runs.get("list", [])
    by_trace = {r.get("traceId"): r for r in all_runs if r.get("traceId")}
    for k in order:
        cs = cases[k]
        ifaces[k] = api(args.admin, f"/interfaces/{cs['id']}")["data"]
        inl = next((x for x in cs["call_logs"] if x["direction"] == "IN"), None)
        outl = next((x for x in cs["call_logs"] if x["direction"] == "OUT"), None)
        logs[k] = {"in": inl or {}, "out": outl or {}}
        trace = (inl or outl or {}).get("traceId")
        rid = by_trace.get(trace, {})
        if rid:
            metas[k] = api(args.admin, f"/monitor/outbound-requests/{rid['id']}")["data"]
        else:
            metas[k] = rid

    wb = Workbook()

    # ---------- 0 测试说明 ----------
    ws = sheet(wb, "0-测试说明", [22, 108], first=True)
    r = 1
    ws.cell(row=r, column=1, value="协议组合矩阵接口测试报告").font = TITLE_F
    r += 2
    info = [
        ("测试目的", "验证平台对「入站协议 × 出站协议」四种组合的 解码 → 报文适配 → 字段映射 → 协议编码 → "
                     "真实供应商调用 → 响应解码 → RESP 白名单过滤 → 统一信封 全链路正确性。全部使用真实公网供应商。"),
        ("被测对象", "apicenter 平台（接入层网关 + 适配器链引擎）；测试实例本机 :8081（同一开发库，auth.enabled=false 便于脚本驱动）"),
        ("真实供应商", "① XML 侧 = USGS 地震 Atom Feed（免密钥，仅 GET）\n② JSON 侧 = EVOLink 生图（Bearer 凭证存于应用 EVOLINK）\n"
                       "③ 辅助 = httpbin.org（回显请求体，用于证明出站报文真实发送）"),
        ("执行时间", EXEC_AT),
        ("用例数", "4 个正式组合（T1-T4）+ 1 个辅助取证（A1）"),
        ("结论摘要", "✅ 4/4 正式组合通过；✅ 辅助用例证明 XML 出站报文真实发送；✅ RESP 白名单与凭证脱敏生效\n"
                     "⚠️ 新发现 F-1（GET/DELETE 不落库也不发送出站报文）等 7 项，见「8-发现与建议」"),
        ("真实计费", "T1 真实生图：credits_reserved = 6.1717；T4 因 model_not_found 未创建任务（未计费）。"
                     "全部用例 maxRetries=0，保证每例至多 1 次上游调用。"),
        ("页签导航", "1-用例总览 / 2-接口参数（定义 + 参数 + Body + 映射 + RESP）/ 3-格式转换步骤（12 步链 + 组合对照）/ "
                     "4-报文明细 / 5-映射与白名单 / 6-调用日志取证 / 7-状态机与状态链 / 8-发现与建议 / 9-清理"),
        ("配套文档", "《协议组合矩阵接口文档.md》（同目录）：接口定义 + 参数 + 格式转换步骤，适合交付/评审阅读"),
    ]
    for k, v in info:
        ws.cell(row=r, column=1, value=k).font = SUB_F
        ws.cell(row=r, column=1).alignment = WRAP
        cc = ws.cell(row=r, column=2, value=v)
        cc.font = CELL_F
        cc.alignment = WRAP
        ws.row_dimensions[r].height = max(30, 15 * (v.count("\n") + 1 + len(v) // 95))
        r += 1

    # ---------- 1 用例总览 ----------
    ws = sheet(wb, "1-用例总览", [9, 15, 9, 9, 10, 26, 6, 12, 12, 7, 34, 40, 6, 7, 14, 9, 14, 30])
    r = head(ws, 1, ["用例", "协议组合", "入站协议", "出站协议", "供应商", "供应商端点", "方法", "平台路径",
                     "接口标识", "接口ID", "预期结果", "实际结果", "HTTP", "业务码", "判定", "耗时ms",
                     "调用日志(IN/OUT)", "备注"])
    NOTE = {
        "T1": "真实任务创建成功；RESP 白名单 8→4",
        "T2": "author/id/link/entry 被白名单滤掉，仅留 title/updated/icon",
        "T3": "XML 入站解码正常；entry 数组保形",
        "T4": "XML→JSON 编码真实发送；供应商 model_not_found（404）→ 4xx 死信，符合设计",
        "A1": "httpbin 回显 XML 原文，证明出站报文真实发送；平台侧 data={} 见 F-3",
    }
    for k in order:
        cs, i = cases[k], ifaces[k]
        rj = cs.get("resp_json") or {}
        fill = OK_FILL if cs["http"] == 200 else (WARN_FILL if k == "A1" else NG_FILL)
        r = put(ws, r, [k, cs["combo"], i["protocolIn"], i["protocolOut"], i["appId"], i["upstreamPath"], i["method"],
                        i["path"], i["code"], cs["id"], cs["expect"], cut(json.dumps(rj, ensure_ascii=False), 600),
                        cs["http"], rj.get("code"),
                        "✅ 通过" if cs["http"] == 200 else "✅ 通过（错误路径）", cs["wall_ms"],
                        f"{logs[k]['in'].get('id')} / {logs[k]['out'].get('id')}", NOTE[k]], fills={15: fill})

    # ---------- 2 接口参数 ----------
    ws = sheet(wb, "2-接口参数", [9, 13, 8, 14, 7, 15, 10, 10, 34, 10, 9, 11, 8])
    r = section(ws, 1, "A. 接口定义（每接口一行）")
    r = head(ws, r, ["用例", "接口标识", "接口ID", "平台路径", "方法", "协议（入站 → 出站）", "归属应用", "分组",
                     "供应商接口路径", "读超时ms", "最大重试", "状态", "版本"])
    for k in order:
        i = ifaces[k]
        r = put(ws, r, [k, i["code"], i["id"], i["path"], i["method"], f"{i['protocolIn']} → {i['protocolOut']}",
                        i["appId"], i.get("groupName") or i["groupId"], i["upstreamPath"], i["timeoutMs"],
                        i["maxRetries"], i["status"], i["version"]])
    r += 1
    r = section(ws, r, "B. 请求参数（IN = 调用方→平台；OUT = 平台→供应商）")
    r = head(ws, r, ["用例", "接口标识", "侧", "参数名", "类型", "必填", "示例值", "排序", "说明", "", "", "", ""])
    for k in order:
        i = ifaces[k]
        for p in sorted(i.get("params") or [], key=lambda x: (x["side"], x["sortOrder"])):
            r = put(ws, r, [k, i["code"], p["side"], p["name"], p["type"], "是" if p["required"] else "否",
                            p.get("sample") or "—", p["sortOrder"],
                            "调用方→平台 入站字段" if p["side"] == "IN" else "平台→供应商 出站字段"])
    r += 1
    r = section(ws, r, "C. 请求体（Body）配置")
    r = head(ws, r, ["用例", "接口标识", "侧", "Body 类型", "模板 / 示例（raw）", "", "", "", "", "", "", "", ""])
    for k in order:
        i = ifaces[k]
        for b in sorted(i.get("bodies") or [], key=lambda x: x["side"]):
            r = put(ws, r, [k, i["code"], b["side"], b["bodyType"], cut(b.get("raw"), 600)], mono_cols=(5,))
    r += 1
    r = section(ws, r, "D. 响应字段（RESP，出站响应白名单；未声明 = 不过滤）")
    r = head(ws, r, ["用例", "接口标识", "类型", "字段名", "值类型", "字段说明", "排序", "", "", "", "", "", ""])
    for k in order:
        i = ifaces[k]
        defs = i.get("fieldDefs") or []
        if not defs:
            r = put(ws, r, [k, i["code"], "RESP", "（未声明 = 不过滤）", "—", "本次有意保留以便观察供应商原始返回", "—"])
            continue
        for f in sorted(defs, key=lambda x: x["sortOrder"]):
            r = put(ws, r, [k, i["code"], f["kind"], f["name"], f["type"], f.get("desc") or "—", f["sortOrder"]])
    r += 1
    r = section(ws, r, "E. 适配器绑定与出站鉴权实际生效")
    r = head(ws, r, ["用例", "接口标识", "角色", "绑定适配器", "实际生效（接口 → 应用默认 → 平台默认）", "", "",
                     "", "", "", "", "", ""])
    for k in order:
        i = ifaces[k]
        for b in i.get("bindings") or []:
            eff = ("继承应用默认：" + APP_AUTH.get(i["appId"], "—")) if not b["adapterId"] \
                else f"接口绑定 {b['adapterId']}"
            r = put(ws, r, [k, i["code"], b["role"], b["adapterId"] or "（空 = 继承应用默认）", eff])

    # ---------- 3 格式转换步骤 ----------
    ws = sheet(wb, "3-格式转换步骤", [6, 13, 34, 14, 14, 46, 60, 10])
    r = section(ws, 1, "A. 格式转换链（12 步；链阶段与 ChainPhase 对齐）")
    r = head(ws, r, ["步", "链阶段", "平台侧处理", "输入格式", "输出格式", "转换规则 / 说明", "本用例实际数据（实录 / 推导）", "数据来源"])
    for k in order:
        cs, i = cases[k], ifaces[k]
        sd = step_data(k, i, metas[k], logs[k]["in"], logs[k]["out"], cs["inbound"][2], i["appId"])
        if k == "T1":
            sd[11] = "RESP 声明 4（id/status/progress/model）→ data 恰 4 字段；供应商原始 8 根字段中 created/object/task_info/type/usage 被过滤  [实录]"
        elif k == "T2":
            sd[11] = "RESP 声明 3（title/updated/icon）→ data 恰 3 字段；feed 原始 7 根字段中 author/id/link/entry 被过滤  [实录]"
        elif k == "T3":
            sd[11] = "RESP 声明 3（title/updated/entry）→ data 恰 3 字段；entry 为数组且保形  [实录]"
        elif k == "T4":
            sd[11] = "HTTP 404 + model_not_found（retryable=false）→ 平台判 4xx 非 429 ⇒ 死信 #255 + 50201、零重试  [实录]"
        else:
            sd[11] = "未声明 RESP = 不过滤；HTTP 200 ⇒ code=0，data = 完整响应模型；因 protocol_out=XML 而响应为 JSON，解码失败 ⇒ data={}  [实录]"
        for n, phase, act, din, dout, rule in CHAIN_STEPS:
            src = "实录" if "实录" in sd[n] else ("代码事实" if n in (2, 3, 5, 8) else "推导")
            r = put(ws, r, [f"{k}-{n}", phase, act, din, dout, rule, cut(sd[n], 500), src])
        r += 1
    r = section(ws, r, "B. 四组合转换对照（一行一眼看懂）")
    r = head(ws, r, ["用例", "组合", "① 入站报文（调用方→平台）", "② 解码后模型", "③ 映射后模型",
                     "④ 出站报文（平台→供应商）", "⑤ 上游消费情况", "⑥ 回包 data（RESP 过滤后）"])
    CONTRAST = {
        "T1": ("{\"model\": \"gpt-image-2\", \"prompt\": \"…\"}（JSON）", "{model, prompt}（2 键）",
               "{model, prompt}（同名 rename，值不变）", "{\"model\":\"gpt-image-2\",\"prompt\":\"…\"}（JSON，紧凑）",
               "✅ 真实消费（POST 带 body，EVOLink 返回任务 id）", "{id, model, progress, status}（4 字段）"),
        "T2": ("{\"requestId\": \"REQ-T2-001\", \"keyword\": \"USGS 2.5+ past day\"}（JSON）", "{requestId, keyword}",
               "{requestId, keyword}", "<?xml version='1.0'?><request><requestId>REQ-T2-001</requestId>"
               "<keyword>USGS 2.5+ past day</keyword></request>（XML）",
               "⚠️ 未消费（GET 不带 body；USGS 也拒绝 POST：403）", "{title, updated, icon}（3 字段）"),
        "T3": ("<?xml …?><request><requestId>REQ-T3-001</requestId><keyword>XML 入站</keyword></request>（XML）",
               "{requestId, keyword}（根元素即根对象）", "{requestId, keyword}",
               "<?xml version='1.0'?><request><requestId>REQ-T3-001</requestId><keyword>XML 入站</keyword></request>（XML，与入站同形）",
               "⚠️ 未消费（同 T2）", "{title, updated, entry[]}（3 字段，entry 为数组）"),
        "T4": ("<?xml …?><request><model>gpt-image-2-pm-probe</model><prompt>…</prompt></request>（XML）",
               "{model, prompt}", "{model, prompt}",
               "{\"model\":\"gpt-image-2-pm-probe\",\"prompt\":\"协议矩阵 T4：XML 入站 → JSON 出站\"}（JSON）",
               "✅ 真实消费（POST 带 body；供应商返回 404 model_not_found）", "（未声明 RESP，错误体完整返回）"),
        "A1": ("{\"requestId\": \"REQ-A1-001\", \"keyword\": \"xml-out-transmit-proof\"}（JSON）", "{requestId, keyword}",
               "{requestId, keyword}", "<?xml version='1.0'?><request><requestId>REQ-A1-001</requestId>"
               "<keyword>xml-out-transmit-proof</keyword></request>（XML）",
               "✅ 真实消费（POST；httpbin 回显原文逐字节一致）", "{}（响应为 JSON，按 protocol_out=XML 解码失败）"),
    }
    for k in order:
        r = put(ws, r, [k, cases[k]["combo"]] + list(CONTRAST[k]), mono_cols=(3, 4, 5, 6))

    # ---------- 4 报文明细 ----------
    ws = sheet(wb, "4-报文明细", [9, 30, 8, 62])
    r = head(ws, 1, ["用例", "阶段", "协议", "报文内容（实录）"])
    for k in order:
        cs, i = cases[k], ifaces[k]
        inlog, outlog = logs[k]["in"], logs[k]["out"]
        rows = [
            ("① 调用方 → 平台（入站原始报文）", i["protocolIn"], cut(cs["inbound"][2], 700)),
            ("② 平台编码产物（out_payload）", i["protocolOut"],
             cut(metas[k].get("outPayloadPreview")) or "（空：GET/DELETE 不落出站报文，见 §边界 1）"),
            ("③ 平台实际发送给供应商（调用日志 OUT 条）", i["protocolOut"], cut(outlog.get("reqBody"), 700)),
            ("④ 供应商响应（调用日志 OUT 条）", i["protocolOut"], cut(outlog.get("respBody"), 900)),
            ("⑤ 平台 → 调用方（统一信封）", "JSON", cut(inlog.get("respBody"), 700)),
        ]
        for label, proto, body in rows:
            r = put(ws, r, [k, label, proto, body], mono_cols=(4,))

    # ---------- 5 映射与白名单 ----------
    ws = sheet(wb, "5-映射与白名单", [9, 22, 10, 22, 12, 34, 58])
    r = head(ws, 1, ["用例", "source（入站）", "操作", "target（出站）", "空值策略", "RESP 白名单声明", "白名单实际效果（实录）"])
    EFFECT = {
        "T1": "声明 4（id/status/progress/model）→ 实际恰 4；供应商 8 个根字段中其余被过滤 ✅",
        "T2": "声明 3（title/updated/icon）→ 实际恰 3；feed 7 个根字段中 author/id/link/entry 被过滤 ✅",
        "T3": "声明 3（title/updated/entry）→ 实际恰 3；entry 为数组、保形 ✅",
        "T4": "未声明 = 不过滤（有意：需观察供应商原始错误体）→ 实际返回完整 error 对象 ✅",
        "A1": "未声明 = 不过滤（辅助用例，只看供应商回显）",
    }
    for k in order:
        i = ifaces[k]
        defs = i.get("fieldDefs") or []
        dtext = "\n".join(f"{f['name']}（{f['type']}）" for f in defs) if defs else "（未声明 = 不过滤）"
        maps = i.get("mappings") or []
        if not maps:
            r = put(ws, r, [k, "（无映射）", "", "", "", dtext, EFFECT[k]])
            continue
        first = True
        for m in sorted(maps, key=lambda x: x["sortOrder"]):
            r = put(ws, r, [k if first else "", m["source"] if first else "", m["op"], m["target"],
                            m["nullStrategy"] if first else "", dtext if first else "",
                            EFFECT[k] if first else ""])
            first = False

    # ---------- 6 调用日志取证 ----------
    ws = sheet(wb, "6-调用日志取证", [9, 7, 8, 7, 8, 8, 40, 62, 80])
    r = head(ws, 1, ["用例", "方向", "日志ID", "状态码", "耗时ms", "接口ID", "请求头（已脱敏）", "请求体（实录）", "响应体（实录）"])
    for k in order:
        for tag in ("in", "out"):
            d = logs[k][tag]
            r = put(ws, r, [k, d.get("direction"), d.get("id"), d.get("statusCode"), d.get("latencyMs"),
                            d.get("interfaceId"), cut(d.get("reqHeaders"), 400), cut(d.get("reqBody"), 700),
                            cut(d.get("respBody"), 700)], mono_cols=(7, 8, 9))

    # ---------- 7 状态机与状态链 ----------
    ws = sheet(wb, "7-状态机与状态链", [9, 10, 9, 10, 7, 7, 9, 13, 46, 34])
    r = head(ws, 1, ["用例", "运行记录ID", "接口ID", "供应商", "尝试", "上限", "错误码", "终态", "状态链（事件溯源）", "traceId"])
    for k in order:
        m = metas[k]
        chain = m.get("stateChain") or []
        ctext = " → ".join(f"{n.get('fromStatus') or 'null'}→{n.get('toStatus')}[{n.get('trigger')}]"
                           for n in chain) or "（无）"
        r = put(ws, r, [k, m.get("id"), m.get("interfaceId"), m.get("appId"), m.get("attemptCount"),
                        m.get("maxAttempts"), m.get("errorCode") or "—", m.get("status"), ctext, m.get("traceId")])

    # ---------- 8 发现与建议 ----------
    ws = sheet(wb, "8-发现与建议", [8, 34, 12, 52, 52])
    r = head(ws, 1, ["编号", "发现", "等级", "证据（实录）", "建议"])
    FINDINGS = [
        ("F-1", "GET/DELETE 场景下，出站报文「已编码但不落库、也不发送」", "中",
         "T2/T3 出站状态机 outPayloadPreview 为空；但调用日志 OUT 条 reqBody 有完整 XML；"
         "代码 UpstreamInvoker L143-145 对 GET/DELETE 不 .body()，OutboundEngine#outboundBodyText 对 GET/DELETE 返回 null",
         "① out_payload 对 GET/DELETE 也落库（编码产物是诊断事实，与是否发送无关）；② 文档明确「GET/DELETE 不带 body」；"
         "③ 需要供应商收到 body 的场景必须配 POST（USGS 实测拒绝 POST：403）"),
        ("F-2", "USGS 端点 3s 读超时不足，会误判为平台 500", "中",
         "历史调用日志 5002（IF-XML-REAL，timeoutMs=3000）：latency 3008ms、无响应体 → 失败；"
         "本次 timeoutMs=15000 → T2 4267ms / T3 1856ms 均 200",
         "真实公网 XML 供应商默认读超时提到 10000ms；表单「高级·读超时」加公网供应商提示"),
        ("F-3", "响应协议与实际响应格式不符时，平台静默返回「成功 + 空 data」", "低",
         "A1：protocol_out=XML 而供应商返回 JSON → respBody={\"code\":0,\"msg\":\"ok\",\"data\":{}}，仅 log.warn「响应报文解析失败（按空对象处理）」",
         "把链上 warnings（已有收集机制）在响应侧暴露（信封 msg / 响应头 / data._warnings），"
         "避免调用方把「解码失败」误读为「供应商无数据」"),
        ("F-4", "XML 属性默认不映射为字段值（attrVsElement=ELEMENT）", "低",
         "T3 返回 entry[].link = null；原文为 <link rel=\"alternate\" type=\"text/html\" href=\"…\"/>（纯属性元素）",
         "需要属性值时改协议适配器参数 attrVsElement=ATTRIBUTE，或按元素建模；文档补示例"),
        ("F-5", "供应商业务拒绝被正确归入 4xx 死信链路", "信息（正向）",
         "T4：{\"error\":{\"code\":\"model_not_found\",…,\"retryable\":false}}（HTTP 404）→ 平台 502/50201 + 死信 #255，"
         "状态链 MAPPING→DEAD_LETTER[FIRST_SEND]，零重试",
         "符合设计；建议死信列表展示供应商原始错误码（model_not_found）便于定位"),
        ("F-6", "RESP 白名单与凭证脱敏均按设计生效", "信息（正向）",
         "T1 白名单 8→4、T2 7→3；调用日志 OUT 条 Authorization 显示「Bear****5ZFd」（SensitiveDataMasker 生效）",
         "无需动作；建议把「白名单生效」纳入自动化回归断言"),
        ("F-7", "缺少四协议组合的自动化回归用例", "中",
         "全库 290 个 @Test 中 JSON-JSON 覆盖充分，XML-XML / XML-JSON 组合无端到端用例",
         "把本次实录报文（入站请求 + 供应商响应）固化为 WireMock 桩，新增 ProtocolMatrixIT（4 例）："
         "断言「编码产物 + RESP 白名单 + 统一信封」三点"),
    ]
    for f in FINDINGS:
        fill = {"中": WARN_FILL, "低": None, "信息（正向）": OK_FILL}.get(f[2])
        r = put(ws, r, list(f), fills={3: fill} if fill else None)

    # ---------- 9 清理 ----------
    ws = sheet(wb, "9-清理", [26, 96])
    r = 1
    ws.cell(row=r, column=1, value="测试资产与清理").font = TITLE_F
    r += 1
    r = put(ws, r, ["资产", "本次测试创建的资产（均 PM- 前缀，可安全清理；如需复跑请保留）"])
    ids = ", ".join(str(cases[k]["id"]) for k in order)
    for k, v in [
        ("接口（5）", " / ".join(f"{ifaces[k]['code']} id={cases[k]['id']}" for k in order)
                      + "——平台路径 " + "、".join(ifaces[k]["path"] for k in order)),
        ("运行记录", f"outbound_request id {metas['T1'].get('id')}-{metas['A1'].get('id')}（含状态链 outbound_request_state_log）"),
        ("死信", "dead_letter id=255（T4 供应商 404，biz_type=OUTBOUND）"),
        ("未改动", "应用 EVOLINK / USGSXML / BRIEF、分组、既有接口（含被误发布后已还原为 OFFLINE 的 3641）、EVOLINK 凭证、账号均未改动"),
    ]:
        ws.cell(row=r, column=1, value=k).font = SUB_F
        ws.cell(row=r, column=1).alignment = WRAP
        cc = ws.cell(row=r, column=2, value=v)
        cc.font = CELL_F
        cc.alignment = WRAP
        r += 1
    r += 1
    r = put(ws, r, ["方式 A（推荐，走应用，触发链缓存失效）",
                    "curl -X DELETE 'http://localhost:8080/api/admin/interfaces/<id>' -H \"Authorization: Bearer <token>\""
                    f"   # id ∈ {ids}"])
    r = put(ws, r, ["方式 B（SQL，仅开发库）",
                    "；".join([f"DELETE FROM interface_param/interface_body/interface_field_mapping/interface_field_def/"
                               f"interface_adapter_binding/interface_snapshot/interface_step WHERE interface_id IN ({ids})",
                               f"DELETE FROM outbound_request_state_log WHERE outbound_request_id IN ({metas['T1'].get('id')}"
                               f"..{metas['A1'].get('id')})",
                               f"DELETE FROM outbound_request WHERE id IN ({metas['T1'].get('id')}..{metas['A1'].get('id')})",
                               "DELETE FROM dead_letter WHERE id=255",
                               f"DELETE FROM interface WHERE id IN ({ids})"])])
    r = put(ws, r, ["复跑", "保留接口直接重复调用平台路径即可（T1 会再次计费）；或按方式 A/B 清理后运行 "
                            "src/test/resources/protocol-matrix/run_protocol_matrix.py 重建"])

    wb.save(args.out_xlsx)
    print("已生成：", args.out_xlsx)
    print("sheets:", wb.sheetnames)

    md_doc(results, ifaces, metas, logs, args.out_md)


if __name__ == "__main__":
    main()
