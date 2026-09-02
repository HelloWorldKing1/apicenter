package com.deepx.apicenter.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 统一内部模型（M0-01 §1）：链内唯一数据载体，格式无关。
 * 树形中间表示：OBJECT（保序 LinkedHashMap + 可选 attributes，仅 XML 使用）/ ARRAY / SCALAR（类型标注）。
 * 点号路径工具（M0-02 §2）：get / set（自动建中间节点）/ remove。
 */
public final class UnifiedModel {

    private UNode root;

    private UnifiedModel(UNode root) {
        this.root = root;
    }

    public static UnifiedModel of(UNode root) {
        return new UnifiedModel(root);
    }

    public static UnifiedModel emptyObject() {
        return new UnifiedModel(new ObjectNode(new LinkedHashMap<>(), Map.of()));
    }

    public UNode root() {
        return root;
    }

    public void root(UNode root) {
        this.root = root;
    }

    // ---------- 点路径操作 ----------

    /** 按点号路径取值（如 filter.seller_id、data.list）；路径不存在或值为 NULL 标量均返回 empty */
    public Optional<UNode> get(String path) {
        if (path == null || path.isBlank()) {
            return Optional.ofNullable(root);
        }
        UNode cur = root;
        for (String seg : path.split("\\.")) {
            if (!(cur instanceof ObjectNode obj)) {
                return Optional.empty();
            }
            cur = obj.fields().get(seg);
            if (cur == null) {
                return Optional.empty();
            }
        }
        if (cur instanceof ScalarNode s && s.type() == ScalarType.NULL) {
            return Optional.empty();
        }
        return Optional.of(cur);
    }

    /** 按点号路径写入，自动创建中间 OBJECT 节点；同名覆盖（M0-02 D1） */
    public void set(String path, UNode value) {
        String[] segs = path.split("\\.");
        if (!(root instanceof ObjectNode obj)) {
            throw new IllegalStateException("根节点必须是 OBJECT 才能按路径写入：" + path);
        }
        ObjectNode cur = obj;
        for (int i = 0; i < segs.length - 1; i++) {
            UNode next = cur.fields().get(segs[i]);
            if (next instanceof ObjectNode o) {
                cur = o;
            } else {
                ObjectNode created = new ObjectNode(new LinkedHashMap<>(), Map.of());
                cur.fields().put(segs[i], created);
                cur = created;
            }
        }
        cur.fields().put(segs[segs.length - 1], value);
    }

    public boolean remove(String path) {
        String[] segs = path.split("\\.");
        if (!(root instanceof ObjectNode obj)) {
            return false;
        }
        ObjectNode cur = obj;
        for (int i = 0; i < segs.length - 1; i++) {
            UNode next = cur.fields().get(segs[i]);
            if (!(next instanceof ObjectNode o)) {
                return false;
            }
            cur = o;
        }
        return cur.fields().remove(segs[segs.length - 1]) != null;
    }

    // ---------- 节点类型 ----------

    /** 标量类型集合（M0-02 §7）：数字精度 INT=long / DECIMAL=BigDecimal（契约 D6 定死） */
    public enum ScalarType { STRING, INT, DECIMAL, BOOLEAN, NULL }

    public sealed interface UNode permits ObjectNode, ArrayNode, ScalarNode {
    }

    /** OBJECT：保序字段 + attributes（仅 XML 适配器使用，默认空） */
    public record ObjectNode(LinkedHashMap<String, UNode> fields, Map<String, String> attributes) implements UNode {
        public static ObjectNode of() {
            return new ObjectNode(new LinkedHashMap<>(), Map.of());
        }
    }

    /** ARRAY：元素列表 */
    public record ArrayNode(List<UNode> items) implements UNode {
        public static ArrayNode of(UNode... nodes) {
            return new ArrayNode(new ArrayList<>(List.of(nodes)));
        }
    }

    /** SCALAR：类型标注 + 原始值 */
    public record ScalarNode(ScalarType type, Object value) implements UNode {
        public static ScalarNode str(String v) {
            return new ScalarNode(ScalarType.STRING, v);
        }

        public static ScalarNode num(long v) {
            return new ScalarNode(ScalarType.INT, v);
        }

        public static ScalarNode decimal(java.math.BigDecimal v) {
            return new ScalarNode(ScalarType.DECIMAL, v);
        }

        public static ScalarNode bool(boolean v) {
            return new ScalarNode(ScalarType.BOOLEAN, v);
        }

        public static ScalarNode nullNode() {
            return new ScalarNode(ScalarType.NULL, null);
        }
    }
}
