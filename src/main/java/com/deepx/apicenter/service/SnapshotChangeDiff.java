package com.deepx.apicenter.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 版本快照变更说明生成器（版本历史 · 变更说明完善，2026-09-07 评审定稿）：
 * - 输入：修改前 config_json 与修改后 config_json（均由 SnapshotSerializer 产出，格式同源）+ 手填备注；
 * - 输出：简洁摘要文本（落 change_note）+ 结构化 JSON（落 change_detail）——主字段 leaf old→new、
 *   子表（params/mappings/fieldDefs/bindings/bodies）按键增删改三态 + 数量摘要；
 * - 口径：文案一律简洁（`变更：…`），不含 operator/reason/时间；body raw 长文只记 hasChanged；
 *   回滚/创建/复制不经过本生成器（各自固定文案）。
 * 保护网：SnapshotChangeDiffTest 覆盖主字段/各子表/空差异/截断。
 */
public final class SnapshotChangeDiff {

    private SnapshotChangeDiff() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 摘要中单值最大展示长度（超长截断 + …，防 change_note 溢出与视图膨胀） */
    private static final int VALUE_MAX = 60;
    /** 摘要总长上限（DB change_note VARCHAR(1000)，留余量防边界字符截断） */
    private static final int SUMMARY_MAX = 900;

    /** 主字段中文 label（顺序即摘要展示顺序；code/status/version/appId 等不参与——status 由生命周期管理） */
    private static final String[][] MAIN_KEYS = {
            {"code", "接口标识"}, {"name", "名称"}, {"ifType", "接口类型"}, {"method", "HTTP 方法"},
            {"path", "平台侧路径"}, {"groupId", "分组"},
            {"protocolIn", "入站协议"}, {"protocolOut", "出站协议"},
            {"upstreamPath", "供应商接口路径"}, {"callbackUrl", "回调地址"},
            {"timeoutMs", "读超时(ms)"}, {"maxRetries", "最大重试"}, {"desc", "描述"}
    };

    public record DiffResult(String summary, String detailJson) {
    }

    /** 生成（编辑保存）：manual 为 X-Change-Note 手填备注（可空） */
    public static DiffResult build(String oldJson, String newJson, String manual) {
        return build0(oldJson, newJson, manual, "UPDATE");
    }

    /** 生成（回滚，2026-09-08）：detail 为「回滚前当前配置 vs 目标版本配置」差异，type=ROLLBACK；
     * 摘要文案不采用（回滚说明固定为「回滚至 v{目标}」），仅取 detail 供版本历史「变更详情」渲染。 */
    public static String rollbackDetail(String oldConfigJson, String targetConfigJson) {
        return build0(oldConfigJson, targetConfigJson, null, "ROLLBACK").detailJson();
    }

    private static DiffResult build0(String oldJson, String newJson, String manual, String type) {
        JsonNode oldRoot = parse(oldJson);
        JsonNode newRoot = parse(newJson);
        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("type", type);

        List<String> parts = new ArrayList<>();

        // ---------- 主字段 ----------
        ArrayNode fields = detail.putArray("fields");
        int fieldCount = 0;
        JsonNode oldMain = oldRoot.path("main");
        JsonNode newMain = newRoot.path("main");
        for (String[] kv : MAIN_KEYS) {
            String key = kv[0], label = kv[1];
            String ov = text(oldMain.get(key));
            String nv = text(newMain.get(key));
            if (!ov.equals(nv)) {
                fieldCount++;
                if (fieldCount <= 3) {
                    parts.add(label + " " + (ov.isEmpty() ? "∅" : shortVal(ov)) + "→" + (nv.isEmpty() ? "∅" : shortVal(nv)));
                }
                ObjectNode f = fields.addObject();
                f.put("field", key).put("label", label).put("old", ov).put("new", nv);
            }
        }
        if (fieldCount > 3) {
            parts.add("等 " + fieldCount + " 项字段");
        }

        // ---------- 子表 ----------
        diffParams(oldRoot, newRoot, detail, parts);
        diffMappings(oldRoot, newRoot, detail, parts);
        diffFieldDefs(oldRoot, newRoot, detail, parts);
        diffBindings(oldRoot, newRoot, detail, parts);
        diffBodies(oldRoot, newRoot, detail, parts);

        detail.put("manual", manual == null ? "" : manual);

        StringBuilder sb = new StringBuilder("变更：");
        if (parts.isEmpty()) {
            sb.append("无字段变化");
        } else {
            sb.append(String.join("；", parts));
        }
        if (manual != null && !manual.isBlank()) {
            sb.append("｜备注：").append(manual.trim());
        }
        if (sb.length() > SUMMARY_MAX) {
            sb.setLength(SUMMARY_MAX - 1);
            sb.append("…");
        }
        return new DiffResult(sb.toString(), detail.toString());
    }

    // ---------- params：key = side/name，增删 + 必填/类型变化计数 ----------
    private static void diffParams(JsonNode oldRoot, JsonNode newRoot, ObjectNode detail, List<String> parts) {
        Map<String, JsonNode> oldM = index(oldRoot.path("params"), n -> n.path("side").asText() + "/" + n.path("name").asText());
        Map<String, JsonNode> newM = index(newRoot.path("params"), n -> n.path("side").asText() + "/" + n.path("name").asText());
        ArrayNode arr = detail.putArray("params");
        int add = 0, del = 0, upd = 0;
        List<String> addNames = new ArrayList<>(), delNames = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : newM.entrySet()) {
            JsonNode oldN = oldM.get(e.getKey());
            if (oldN == null) {
                add++;
                if (addNames.size() < 2) {
                    addNames.add(e.getKey());
                }
                arr.addObject().put("action", "ADD").put("key", e.getKey()).put("type", text(e.getValue().get("type")));
            } else if (changed(oldN, e.getValue(), "type", "required", "sample")) {
                upd++;
                arr.addObject().put("action", "UPD").put("key", e.getKey());
            }
        }
        for (Map.Entry<String, JsonNode> e : oldM.entrySet()) {
            if (!newM.containsKey(e.getKey())) {
                del++;
                if (delNames.size() < 2) {
                    delNames.add(e.getKey());
                }
                arr.addObject().put("action", "DEL").put("key", e.getKey());
            }
        }
        if (add > 0 || del > 0 || upd > 0) {
            List<String> bits = new ArrayList<>();
            if (add > 0) {
                bits.add("新增 " + join(add, addNames));
            }
            if (del > 0) {
                bits.add("删除 " + join(del, delNames));
            }
            if (upd > 0) {
                bits.add("修改 " + upd + " 条");
            }
            parts.add("参数 " + oldM.size() + "→" + newM.size() + "（" + String.join("、", bits) + "）");
        }
    }

    // ---------- mappings：数量 + 新增/删除条目（source→target）+ 修改计数 ----------
    private static void diffMappings(JsonNode oldRoot, JsonNode newRoot, ObjectNode detail, List<String> parts) {
        JsonNode oldArr = oldRoot.path("mappings");
        JsonNode newArr = newRoot.path("mappings");
        Map<String, JsonNode> oldM = index(oldArr, MAPPING_KEY);
        Map<String, JsonNode> newM = index(newArr, MAPPING_KEY);
        ArrayNode arr = detail.putArray("mappings");
        int add = 0, del = 0, upd = 0;
        List<String> addList = new ArrayList<>(), delList = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : newM.entrySet()) {
            JsonNode oldN = oldM.get(e.getKey());
            if (oldN == null) {
                add++;
                if (addList.size() < 2) {
                    addList.add(e.getKey());
                }
                arr.addObject().put("action", "ADD").put("key", e.getKey());
            } else if (changed(oldN, e.getValue(), "param", "nullStrategy")) {
                upd++;
                arr.addObject().put("action", "UPD").put("key", e.getKey());
            }
        }
        for (Map.Entry<String, JsonNode> e : oldM.entrySet()) {
            if (!newM.containsKey(e.getKey())) {
                del++;
                if (delList.size() < 2) {
                    delList.add(e.getKey());
                }
                arr.addObject().put("action", "DEL").put("key", e.getKey());
            }
        }
        if (add > 0 || del > 0 || upd > 0) {
            List<String> bits = new ArrayList<>();
            if (add > 0) {
                bits.add("新增 " + join(add, addList));
            }
            if (del > 0) {
                bits.add("删除 " + join(del, delList));
            }
            if (upd > 0) {
                bits.add("修改 " + upd + " 条");
            }
            parts.add("字段映射 " + oldM.size() + "→" + newM.size() + "（" + String.join("、", bits) + "）");
        }
    }

    // ---------- fieldDefs：key = kind/name ----------
    private static void diffFieldDefs(JsonNode oldRoot, JsonNode newRoot, ObjectNode detail, List<String> parts) {
        Map<String, JsonNode> oldM = index(oldRoot.path("fieldDefs"), n -> n.path("kind").asText() + "/" + n.path("name").asText());
        Map<String, JsonNode> newM = index(newRoot.path("fieldDefs"), n -> n.path("kind").asText() + "/" + n.path("name").asText());
        ArrayNode arr = detail.putArray("fieldDefs");
        int add = 0, del = 0, upd = 0;
        List<String> addList = new ArrayList<>(), delList = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : newM.entrySet()) {
            JsonNode oldN = oldM.get(e.getKey());
            if (oldN == null) {
                add++;
                if (addList.size() < 2) {
                    addList.add(e.getKey());
                }
                arr.addObject().put("action", "ADD").put("key", e.getKey());
            } else if (changed(oldN, e.getValue(), "type", "desc")) {
                upd++;
                arr.addObject().put("action", "UPD").put("key", e.getKey());
            }
        }
        for (Map.Entry<String, JsonNode> e : oldM.entrySet()) {
            if (!newM.containsKey(e.getKey())) {
                del++;
                if (delList.size() < 2) {
                    delList.add(e.getKey());
                }
                arr.addObject().put("action", "DEL").put("key", e.getKey());
            }
        }
        if (add > 0 || del > 0 || upd > 0) {
            List<String> bits = new ArrayList<>();
            if (add > 0) {
                bits.add("新增 " + join(add, addList));
            }
            if (del > 0) {
                bits.add("删除 " + join(del, delList));
            }
            if (upd > 0) {
                bits.add("修改 " + upd + " 条");
            }
            parts.add("响应/ack 字段 " + oldM.size() + "→" + newM.size() + "（" + String.join("、", bits) + "）");
        }
    }

    // ---------- bindings：key = role ----------
    private static void diffBindings(JsonNode oldRoot, JsonNode newRoot, ObjectNode detail, List<String> parts) {
        Map<String, JsonNode> oldM = index(oldRoot.path("bindings"), n -> n.path("role").asText());
        Map<String, JsonNode> newM = index(newRoot.path("bindings"), n -> n.path("role").asText());
        ArrayNode arr = detail.putArray("bindings");
        List<String> bits = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : newM.entrySet()) {
            JsonNode oldN = oldM.get(e.getKey());
            if (oldN == null) {
                arr.addObject().put("action", "ADD").put("key", e.getKey()).put("new", adapterOf(e.getValue()));
                bits.add(e.getKey() + " 绑定 " + adapterOf(e.getValue()));
            } else {
                String ov = adapterOf(oldN);
                String nv = adapterOf(e.getValue());
                if (!ov.equals(nv)) {
                    ObjectNode node = arr.addObject();
                    node.put("action", "UPD").put("key", e.getKey()).put("old", ov).put("new", nv);
                    bits.add(e.getKey() + " " + (ov.isEmpty() ? "∅" : ov) + "→" + (nv.isEmpty() ? "∅" : nv));
                }
            }
        }
        for (Map.Entry<String, JsonNode> e : oldM.entrySet()) {
            if (!newM.containsKey(e.getKey())) {
                arr.addObject().put("action", "DEL").put("key", e.getKey()).put("old", adapterOf(e.getValue()));
                bits.add("解绑 " + e.getKey());
            }
        }
        if (!bits.isEmpty()) {
            parts.add("绑定 " + String.join("、", bits));
        }
    }

    // ---------- bodies：side 上 bodyType/raw 变化（raw 只记 hasChanged） ----------
    private static void diffBodies(JsonNode oldRoot, JsonNode newRoot, ObjectNode detail, List<String> parts) {
        Map<String, JsonNode> oldM = index(oldRoot.path("bodies"), n -> n.path("side").asText());
        Map<String, JsonNode> newM = index(newRoot.path("bodies"), n -> n.path("side").asText());
        ArrayNode arr = detail.putArray("bodies");
        for (Map.Entry<String, JsonNode> e : newM.entrySet()) {
            JsonNode oldN = oldM.get(e.getKey());
            if (oldN == null) {
                continue;
            }
            String ot = text(oldN.get("bodyType"));
            String nt = text(e.getValue().get("bodyType"));
            boolean rawChanged = !text(oldN.get("raw")).equals(text(e.getValue().get("raw")));
            boolean formChanged = !text(oldN.get("form")).equals(text(e.getValue().get("form")));
            if (!ot.equals(nt) || rawChanged || formChanged) {
                ObjectNode node = arr.addObject();
                node.put("action", "UPD").put("key", e.getKey());
                node.put("bodyType", ot + "→" + nt);
                node.put("hasChanged", rawChanged || formChanged);
                parts.add("Body(" + e.getKey() + ") "
                        + (!ot.equals(nt)
                        ? (ot.isEmpty() ? "∅" : ot) + "→" + (nt.isEmpty() ? "∅" : nt)
                        : "内容有改动"));
            }
        }
    }

    // ---------- helpers ----------

    private static final java.util.function.Function<JsonNode, String> MAPPING_KEY =
            n -> text(n.get("source")) + "→" + text(n.get("target"));

    private static Map<String, JsonNode> index(JsonNode arr, java.util.function.Function<JsonNode, String> keyFn) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (arr == null) {
            return out;
        }
        for (JsonNode n : arr) {
            out.putIfAbsent(keyFn.apply(n), n);
        }
        return out;
    }

    private static String adapterOf(JsonNode n) {
        String id = text(n.get("adapterId"));
        if (id.isEmpty()) {
            return "";
        }
        String v = text(n.get("version"));
        return v.isEmpty() ? id : id + ":" + v;
    }

    private static boolean changed(JsonNode oldN, JsonNode newN, String... keys) {
        for (String k : keys) {
            if (!text(oldN.get(k)).equals(text(newN.get(k)))) {
                return true;
            }
        }
        return false;
    }

    private static String join(int count, List<String> names) {
        if (count <= 2) {
            return String.join("、", names);
        }
        return String.join("、", names) + " 等 " + count + " 条";
    }

    private static String shortVal(String v) {
        if (v == null || v.length() <= VALUE_MAX) {
            return v;
        }
        return v.substring(0, VALUE_MAX) + "…";
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        return n.asText();
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("快照 config_json 非法：" + e.getMessage());
        }
    }
}
