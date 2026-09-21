#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
协议组合矩阵真实用例执行脚本（4 组合 + 1 辅助）
针对 :8081 实例（auth.enabled=false），同一开发库。
仅使用标准库（urllib）。
"""
import json
import time
import urllib.request
import urllib.error

ADMIN = "http://localhost:8081/api/admin"
GATEWAY = "http://localhost:8081"
EVOLINK_MODEL_VALID = "gpt-image-2"
EVOLINK_GROUP = 1          # EVOLINK 默认分组
USGS_GROUP = 2131          # USGSXML default
BRIEF_GROUP = None         # 运行时查

USGS_ATOM = "/earthquakes/feed/v1.0/summary/2.5_day.atom"
EVL_GEN = "/v1/images/generations"


def call(method, url, body=None, headers=None, timeout=60):
    data = None
    hdrs = dict(headers or {})
    if body is not None:
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        hdrs.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace"), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), dict(e.headers)
    except Exception as e:                                    # noqa: BLE001
        return 0, f"<transport error: {type(e).__name__}: {e}>", {}


def api(method, path, payload=None):
    st, txt, _ = call(method, ADMIN + path,
                      json.dumps(payload, ensure_ascii=False) if payload is not None else None,
                      {"Content-Type": "application/json"})
    try:
        return st, json.loads(txt)
    except Exception:                                          # noqa: BLE001
        return st, {"raw": txt[:400]}


def outbound_iface(code, name, method, path, app_id, group_id, upstream, pin, pout,
                   params, bodies, mappings, field_defs, timeout_ms, max_retries, desc):
    return {
        "code": code, "name": name, "ifType": "OUTBOUND", "method": method, "path": path,
        "protocolIn": pin, "protocolOut": pout, "appId": app_id, "groupId": group_id,
        "upstreamPath": upstream, "callbackUrl": None, "status": "DRAFT",
        "timeoutMs": timeout_ms, "maxRetries": max_retries, "desc": desc, "version": 1.0,
        "params": params, "bodies": bodies, "mappings": mappings,
        "fieldDefs": field_defs,
        "bindings": [{"role": "AUTH", "adapterId": None, "version": None},
                     {"role": "MESSAGE", "adapterId": None, "version": None}],
        "steps": [],
    }


def P(side, name, typ, req, sample, order):
    return {"side": side, "name": name, "type": typ, "required": req,
            "sample": sample, "sortOrder": order}


def B(side, body_type, raw):
    return {"side": side, "bodyType": body_type, "raw": raw, "form": "[]"}


def M(source, op, target, order, param=None, null_strategy="KEEP"):
    return {"source": source, "op": op, "target": target, "param": param,
            "nullStrategy": null_strategy, "sortOrder": order}


def F(kind, name, typ, desc, order):
    return {"kind": kind, "name": name, "type": typ, "desc": desc, "sortOrder": order}


# ---------------- 用例定义 ----------------
CASES = []

# T1 JSON → JSON （EVOLink，POST，真实发送）
CASES.append(dict(
    key="T1", combo="JSON→JSON", iface=outbound_iface(
        "PM-T1-JJ", "协议矩阵 T1 JSON→JSON（EVOLink 真实生图）", "POST", "/pm/t1-jj",
        "EVOLINK", EVOLINK_GROUP, EVL_GEN, "JSON", "JSON",
        [P("IN", "model", "string", True, EVOLINK_MODEL_VALID, 0),
         P("IN", "prompt", "string", True, "协议矩阵 T1", 1),
         P("OUT", "model", "string", True, None, 0),
         P("OUT", "prompt", "string", True, None, 1)],
        [B("IN", "json", '{"model":"%s","prompt":"协议矩阵 T1：JSON 入站 → JSON 出站"}' % EVOLINK_MODEL_VALID),
         B("OUT", "none", "")],
        [M("model", "rename", "model", 0), M("prompt", "rename", "prompt", 1)],
        [F("RESP", "id", "string", "任务 ID", 0),
         F("RESP", "status", "string", "任务状态", 1),
         F("RESP", "progress", "number", "进度", 2),
         F("RESP", "model", "string", "模型", 3)],
        15000, 0, "协议组合矩阵 T1：调用方 JSON → 平台 → EVOLink（JSON）。真实计费调用，maxRetries=0 保证仅 1 次。"),
    inbound=('POST', '/pm/t1-jj',
             json.dumps({"model": EVOLINK_MODEL_VALID,
                         "prompt": "协议矩阵 T1：JSON 入站 → JSON 出站（真实 EVOLink 调用）"},
                        ensure_ascii=False),
             {"Content-Type": "application/json", "X-Biz-Id": "PM-T1-001"}),
    expect="HTTP 200 + code=0；data 含 id/status（RESP 白名单仅这 4 字段）",
))

# T2 JSON → XML （USGS，GET）
CASES.append(dict(
    key="T2", combo="JSON→XML", iface=outbound_iface(
        "PM-T2-JX", "协议矩阵 T2 JSON→XML（USGS atom 真实 XML 响应）", "GET", "/pm/t2-jx",
        "USGSXML", USGS_GROUP, USGS_ATOM, "JSON", "XML",
        [P("IN", "requestId", "string", True, "REQ-T2-001", 0),
         P("IN", "keyword", "string", True, "USGS 2.5+ past day", 1),
         P("OUT", "requestId", "string", False, None, 0),
         P("OUT", "keyword", "string", False, None, 1)],
        [B("IN", "json", '{"requestId":"REQ-T2-001","keyword":"USGS 2.5+ past day"}'), B("OUT", "none", "")],
        [M("requestId", "rename", "requestId", 0), M("keyword", "rename", "keyword", 1)],
        [F("RESP", "title", "string", "feed 标题", 0),
         F("RESP", "updated", "string", "feed 更新时间", 1),
         F("RESP", "icon", "string", "feed 图标", 2)],
        15000, 2, "协议组合矩阵 T2：调用方 JSON → 平台 → USGS（XML）。RESP 白名单只放 title/updated/icon。"),
    inbound=('GET', '/pm/t2-jx',
             json.dumps({"requestId": "REQ-T2-001", "keyword": "USGS 2.5+ past day"}, ensure_ascii=False),
             {"Content-Type": "application/json", "X-Biz-Id": "PM-T2-001"}),
    expect="HTTP 200 + code=0；data={title,updated,icon}（author/id/link/entry 被白名单滤掉）",
))

# T3 XML → XML （USGS，GET）
CASES.append(dict(
    key="T3", combo="XML→XML", iface=outbound_iface(
        "PM-T3-XX", "协议矩阵 T3 XML→XML（USGS atom 真实 XML 响应）", "GET", "/pm/t3-xx",
        "USGSXML", USGS_GROUP, USGS_ATOM, "XML", "XML",
        [P("IN", "requestId", "string", True, "REQ-T3-001", 0),
         P("IN", "keyword", "string", True, "XML 入站", 1),
         P("OUT", "requestId", "string", False, None, 0),
         P("OUT", "keyword", "string", False, None, 1)],
        [B("IN", "xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><request><requestId>REQ-T3-001</requestId>"
                        "<keyword>XML 入站</keyword></request>"), B("OUT", "none", "")],
        [M("requestId", "rename", "requestId", 0), M("keyword", "rename", "keyword", 1)],
        [F("RESP", "title", "string", "feed 标题", 0),
         F("RESP", "updated", "string", "feed 更新时间", 1),
         F("RESP", "entry", "array", "地震条目数组", 2)],
        15000, 2, "协议组合矩阵 T3：调用方 XML → 平台 → USGS（XML）。含 entry 数组白名单。"),
    inbound=('GET', '/pm/t3-xx',
             '<?xml version="1.0" encoding="UTF-8"?><request><requestId>REQ-T3-001</requestId>'
             '<keyword>XML 入站</keyword></request>',
             {"Content-Type": "application/xml", "X-Biz-Id": "PM-T3-001"}),
    expect="HTTP 200 + code=0；data={title,updated,entry[]}（entry 为数组）",
))

# T4 XML → JSON （EVOLink，POST，真实发送；用非法模型走错误路径，避免重复计费）
CASES.append(dict(
    key="T4", combo="XML→JSON", iface=outbound_iface(
        "PM-T4-XJ", "协议矩阵 T4 XML→JSON（EVOLink）", "POST", "/pm/t4-xj",
        "EVOLINK", EVOLINK_GROUP, EVL_GEN, "XML", "JSON",
        [P("IN", "model", "string", True, "gpt-image-2-pm-probe", 0),
         P("IN", "prompt", "string", True, "协议矩阵 T4", 1),
         P("OUT", "model", "string", True, None, 0),
         P("OUT", "prompt", "string", True, None, 1)],
        [B("IN", "xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><request>"
                        "<model>gpt-image-2-pm-probe</model><prompt>协议矩阵 T4</prompt></request>"),
         B("OUT", "none", "")],
        [M("model", "rename", "model", 0), M("prompt", "rename", "prompt", 1)],
        [],                                       # RESP 不声明 = 不过滤，便于观察供应商原始返回
        15000, 0, "协议组合矩阵 T4：调用方 XML → 平台 → EVOLink（JSON）。故意用非法模型走的错误路径（XML→JSON 编码同样被真实发送）。"),
    inbound=('POST', '/pm/t4-xj',
             '<?xml version="1.0" encoding="UTF-8"?><request><model>gpt-image-2-pm-probe</model>'
             '<prompt>协议矩阵 T4：XML 入站 → JSON 出站</prompt></request>',
             {"Content-Type": "application/xml", "X-Biz-Id": "PM-T4-001"}),
    expect="链路应全通；预期供应商业务错误（HTTP 4xx/2xx+错误体），XML→JSON 编码产物见调用日志 OUT 条 req_body",
))

# A1 辅助：JSON → XML 且 POST（httpbin 回显 body）—— 证明 XML 出站报文真的被发出
CASES.append(dict(
    key="A1", combo="JSON→XML（辅助·传输取证）", auxiliary=True, iface=outbound_iface(
        "PM-A1-XT", "协议矩阵 A1 辅助：XML 出站报文发送取证（httpbin 回显）", "POST", "/pm/a1-xml-tx",
        "BRIEF", None, "/post", "JSON", "XML",
        [P("IN", "requestId", "string", True, "REQ-A1-001", 0),
         P("IN", "keyword", "string", True, "xml-out-transmit-proof", 1),
         P("OUT", "requestId", "string", False, None, 0),
         P("OUT", "keyword", "string", False, None, 1)],
        [B("IN", "json", '{"requestId":"REQ-A1-001","keyword":"xml-out-transmit-proof"}'), B("OUT", "none", "")],
        [M("requestId", "rename", "requestId", 0), M("keyword", "rename", "keyword", 1)],
        [],
        15000, 0, "辅助用例（非 4 组合之一）：POST + protocol_out=XML 打到 httpbin/post，上游把收到的原始 body 回显，"
                   "用于证明「XML 出站报文真的发出去了」（GET/DELETE 不传 body，见 UpstreamInvoker）。"),
    inbound=('POST', '/pm/a1-xml-tx',
             json.dumps({"requestId": "REQ-A1-001", "keyword": "xml-out-transmit-proof"}, ensure_ascii=False),
             {"Content-Type": "application/json", "X-Biz-Id": "PM-A1-001"}),
    expect="HTTP 200；调用日志 OUT 条 resp_body 的 data 字段包含 <request><requestId>REQ-A1-001</requestId>…（= 平台发出的 XML 原文）",
))


def main():
    results = {"created": [], "runs": []}

    # 0. 查 BRIEF 分组 id
    st, r = api("GET", "/groups?appId=BRIEF")
    if r.get("data"):
        brief_group = r["data"][0]["id"]
    else:
        brief_group = None
    print("BRIEF groupId =", brief_group)

    # 1. 建接口 + 发布
    for c in CASES:
        iface = c["iface"]
        if iface["appId"] == "BRIEF":
            iface["groupId"] = brief_group
        st, r = api("POST", "/interfaces", iface)
        print(f"[create] {iface['code']} HTTP={st} -> {r.get('code')} {r.get('msg')}")
        if r.get("code") != 0:
            c["create_error"] = r
            continue
        st, r = api("GET", f"/interfaces?keyword={iface['code']}")
        rows = r.get("data") or []
        row = next((x for x in rows if x["code"] == iface["code"]), None)
        if not row:
            c["create_error"] = {"msg": "创建后查不到接口", "list": rows}
            continue
        c["id"] = row["id"]
        st, r = api("POST", f"/interfaces/{row['id']}/publish")
        print(f"[publish] {iface['code']} id={row['id']} HTTP={st} code={r.get('code')} {r.get('msg')}")
        c["publish_ok"] = r.get("code") == 0
        results["created"].append({"code": iface["code"], "id": row["id"], "path": iface["path"],
                                   "appId": iface["appId"], "combo": c["combo"]})
        time.sleep(0.3)

    # 2. 执行调用
    for c in CASES:
        if "id" not in c:
            continue
        method, path, body, headers = c["inbound"]
        t0 = time.time()
        st, txt, _ = call(method, GATEWAY + path, body, headers, timeout=60)
        ms = int((time.time() - t0) * 1000)
        print(f"[run] {c['key']} {method} {path} -> HTTP {st} ({ms} ms)")
        print("      ", txt[:400].replace("\n", " "))
        c["http"] = st
        c["resp_text"] = txt
        c["wall_ms"] = ms
        try:
            c["resp_json"] = json.loads(txt)
        except Exception:                                       # noqa: BLE001
            c["resp_json"] = None
        time.sleep(1.5)

    # 3. 取证：调用日志 OUT 条
    for c in CASES:
        if "id" not in c:
            continue
        st, r = api("GET", f"/monitor/call-logs?interfaceId={c['id']}&page=0&size=10")
        rows = r.get("data") or []
        if isinstance(rows, dict):
            rows = rows.get("list", [])
        detail = []
        for row in rows[:4]:
            st2, d = api("GET", f"/monitor/call-logs/{row['id']}")
            detail.append(d.get("data"))
        c["call_logs"] = detail
        print(f"[evidence] {c['key']} iface={c['id']} call_logs={len(detail)}")

    results["cases"] = [{k: v for k, v in c.items()} for c in CASES]
    with open("/tmp/pm_matrix_results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("\n已写出 /tmp/pm_matrix_results.json")


if __name__ == "__main__":
    main()
