package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.InterfaceDtos.BodyDto;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.FieldDefDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.MappingDto;
import com.deepx.apicenter.dto.InterfaceDtos.ParamDto;
import com.deepx.apicenter.model.InterfaceRow;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 接口配置快照序列化器（M5 D-M5-1）：
 * - config_json ↔ 六段（main / params / bodies / mappings / fieldDefs / bindings）完整可重建；
 * - 不含 status（回滚不动生命周期）；含 appId / groupId（换过应用的接口回滚不恢复错归属）；
 * - 解析按显式键取值、数组缺省容忍、未知字段忽略（向前兼容，防结构漂移）。
 * 保护网：SnapshotSerializerTest 往返等价（加子表 / 加字段必须同步本类并回改该测试）。
 */
@Component
public class SnapshotSerializer {

    private final ObjectMapper objectMapper;

    public SnapshotSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ---------- 序列化：行集 → config_json ----------

    public String toJson(InterfaceRow row,
                         List<InterfaceRow.ParamRow> params,
                         List<InterfaceRow.BodyRow> bodies,
                         List<InterfaceRow.MappingRow> mappings,
                         List<InterfaceRow.FieldDefRow> fieldDefs,
                         List<InterfaceRow.BindingRow> bindings) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode main = root.putObject("main");
        main.put("code", nz(row.code()));
        main.put("name", nz(row.name()));
        main.put("ifType", nz(row.ifType()));
        main.put("method", nz(row.method()));
        main.put("path", nz(row.path()));
        main.put("protocolIn", nz(row.protocolIn()));
        main.put("protocolOut", nz(row.protocolOut()));
        main.put("upstreamPath", nz(row.upstreamPath()));
        main.put("callbackUrl", nz(row.callbackUrl()));
        main.put("appId", nz(row.appId()));
        main.put("groupId", row.groupId());
        main.put("timeoutMs", row.timeoutMs());
        main.put("maxRetries", row.maxRetries());
        main.put("desc", nz(row.desc()));

        ArrayNode paramsArr = root.putArray("params");
        for (InterfaceRow.ParamRow p : params) {
            ObjectNode n = paramsArr.addObject();
            n.put("side", p.side());
            n.put("name", p.name());
            n.put("type", nz(p.type()));
            n.put("required", p.required());
            n.put("sample", nz(p.sample()));
            n.put("sortOrder", p.sortOrder());
        }
        ArrayNode bodiesArr = root.putArray("bodies");
        for (InterfaceRow.BodyRow b : bodies) {
            ObjectNode n = bodiesArr.addObject();
            n.put("side", b.side());
            n.put("bodyType", nz(b.bodyType()));
            n.put("raw", nz(b.raw()));
            n.put("form", nz(b.form()));
        }
        ArrayNode mappingsArr = root.putArray("mappings");
        for (InterfaceRow.MappingRow m : mappings) {
            ObjectNode n = mappingsArr.addObject();
            n.put("source", nz(m.source()));
            n.put("op", m.op());
            n.put("target", m.target());
            n.put("param", nz(m.param()));
            n.put("nullStrategy", nz(m.nullStrategy()));
            n.put("sortOrder", m.sortOrder());
        }
        ArrayNode fieldDefsArr = root.putArray("fieldDefs");
        for (InterfaceRow.FieldDefRow f : fieldDefs) {
            ObjectNode n = fieldDefsArr.addObject();
            n.put("kind", f.kind());
            n.put("name", f.name());
            n.put("type", nz(f.type()));
            n.put("desc", nz(f.desc()));
            n.put("sortOrder", f.sortOrder());
        }
        ArrayNode bindingsArr = root.putArray("bindings");
        for (InterfaceRow.BindingRow b : bindings) {
            ObjectNode n = bindingsArr.addObject();
            n.put("role", b.role());
            n.put("adapterId", nz(b.adapterId()));
            n.put("version", nz(b.version()));
        }
        return root.toString();
    }

    // ---------- 反序列化：config_json → InterfaceRequest（回滚走既有全量替换路径） ----------

    /** 回滚专用：version 由调用方（乐观锁 currentVersion）传入；status 快照不含，由 update 保留当前值 */
    public InterfaceRequest toRequest(String configJson, BigDecimal version) {
        JsonNode root;
        try {
            root = objectMapper.readTree(configJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("快照 config_json 非法：" + e.getMessage());
        }
        JsonNode main = root.path("main");
        List<ParamDto> params = parseParams(root.path("params"));
        List<BodyDto> bodies = parseBodies(root.path("bodies"));
        List<MappingDto> mappings = parseMappings(root.path("mappings"));
        List<FieldDefDto> fieldDefs = parseFieldDefs(root.path("fieldDefs"));
        List<BindingDto> bindings = parseBindings(root.path("bindings"));
        return new InterfaceRequest(
                text(main, "code"), text(main, "name"), text(main, "ifType"), text(main, "method"),
                text(main, "path"), text(main, "protocolIn"), text(main, "protocolOut"),
                text(main, "appId"), main.path("groupId").asLong(),
                nullable(main, "upstreamPath"), nullable(main, "callbackUrl"),
                null, // status：快照不含，回滚保留当前生命周期状态
                main.path("timeoutMs").asInt(3000), main.path("maxRetries").asInt(4),
                nullable(main, "desc"),
                version, params, bodies, mappings, fieldDefs, bindings);
    }

    private List<ParamDto> parseParams(JsonNode arr) {
        List<ParamDto> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(new ParamDto(text(n, "side"), text(n, "name"), text(n, "type"),
                    n.path("required").asBoolean(false), nullable(n, "sample"),
                    intOrNull(n, "sortOrder")));
        }
        return out;
    }

    private List<BodyDto> parseBodies(JsonNode arr) {
        List<BodyDto> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(new BodyDto(text(n, "side"), text(n, "bodyType"), nullable(n, "raw"), nullable(n, "form")));
        }
        return out;
    }

    private List<MappingDto> parseMappings(JsonNode arr) {
        List<MappingDto> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(new MappingDto(nullable(n, "source"), text(n, "op"), text(n, "target"),
                    nullable(n, "param"), text(n, "nullStrategy"), intOrNull(n, "sortOrder")));
        }
        return out;
    }

    private List<FieldDefDto> parseFieldDefs(JsonNode arr) {
        List<FieldDefDto> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(new FieldDefDto(text(n, "kind"), text(n, "name"), text(n, "type"),
                    nullable(n, "desc"), intOrNull(n, "sortOrder")));
        }
        return out;
    }

    private List<BindingDto> parseBindings(JsonNode arr) {
        List<BindingDto> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(new BindingDto(text(n, "role"), nullable(n, "adapterId"), nullable(n, "version")));
        }
        return out;
    }

    // ---------- helpers ----------

    private String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? "" : v.asText();
    }

    /** 可空字段：缺省 / null / 空字符串统一归一化为 null（与库表空值口径一致） */
    private String nullable(JsonNode n, String key) {
        JsonNode v = n.path(key);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private Integer intOrNull(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asInt();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
