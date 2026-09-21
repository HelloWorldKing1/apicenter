package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.InterfaceDtos.BodyDto;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.FieldDefDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.MappingDto;
import com.deepx.apicenter.dto.InterfaceDtos.ParamDto;
import com.deepx.apicenter.dto.InterfaceDtos.StepDto;
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
 * - config_json ↔ 七段（main / params / bodies / mappings / fieldDefs / bindings / **steps**）完整可重建；
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
                         List<InterfaceRow.BindingRow> bindings,
                         /** 前置步骤（编排，第七段）；调用方须传实参——不留默认重载，避免静默丢步骤 */
                         List<InterfaceRow.StepView> steps) {
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
        // 协议参数（B1）：以【解析后的 JsonNode】入快照，不是裸字符串 ——
        // 存字符串会双重转义（"{\"xml\":…}"），使版本变更详情（SnapshotChangeDiff）不可读
        putProtocolParams(main, row.protocolParams());

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
        // 前置步骤（编排，第七段）：存 targetCode（跨环境 / 跨库可移植；回滚时解析回 id）
        ArrayNode stepsArr = root.putArray("steps");
        for (InterfaceRow.StepView s : steps) {
            ObjectNode n = stepsArr.addObject();
            n.put("seq", s.seq());
            n.put("stepCode", s.stepCode());
            n.put("targetCode", s.targetCode());
            n.put("failurePolicy", nz(s.failurePolicy()));
            n.put("enabled", s.enabled());
        }
        return root.toString();
    }

    // ---------- 反序列化：config_json → InterfaceRequest（回滚走既有全量替换路径） ----------

    /**
     * targetCode → id 解析器（回滚专用）：快照里只存 code（跨环境 / 跨库可移植），
     * 回滚时由调用方（拥有仓储的 InterfaceService）解析回 id，解析失败必须报 40001。
     * 用函数式参数而非直接依赖仓储：本类保持纯序列化器，单测无需 Spring / 数据。
     */
    @FunctionalInterface
    public interface StepTargetResolver {
        long resolve(String targetCode);
    }

    /** 回滚专用：version 由调用方（乐观锁 currentVersion）传入；status 快照不含，由 update 保留当前值 */
    public InterfaceRequest toRequest(String configJson, BigDecimal version) {
        return toRequest(configJson, version, code -> {
            throw com.deepx.apicenter.exception.BizException.fieldInvalid("快照引用的前置接口不存在：" + code);
        });
    }

    /** 同 {@link #toRequest(String, BigDecimal)}；steps 的 targetCode 经 resolver 解析回 id */
    public InterfaceRequest toRequest(String configJson, BigDecimal version, StepTargetResolver resolver) {
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
        List<StepDto> steps = parseSteps(root.path("steps"), resolver);
        return new InterfaceRequest(
                text(main, "code"), text(main, "name"), text(main, "ifType"), text(main, "method"),
                text(main, "path"), text(main, "protocolIn"), text(main, "protocolOut"),
                text(main, "appId"), main.path("groupId").asLong(),
                nullable(main, "upstreamPath"), nullable(main, "callbackUrl"),
                null, // status：快照不含，回滚保留当前生命周期状态
                main.path("timeoutMs").asInt(3000), main.path("maxRetries").asInt(4),
                nullable(main, "desc"),
                version, params, bodies, mappings, fieldDefs, bindings, steps, protocolParamsText(main));
    }

    /**
     * 读快照 main.protocolParams（协议参数，B1）：
     * 新快照存为【对象节点】→ 转回 JSON 文本；旧/兜底形态可能为【字符串节点】→ 直取。
     * 缺失 / null → null（= 接口用内置默认，即改造前行为）。
     */
    private String protocolParamsText(JsonNode main) {
        JsonNode n = main.path("protocolParams");
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    /** 写快照 main.protocolParams：解析为 JsonNode 写入；非法 JSON 时原样存字符串（读取侧兼容） */
    private void putProtocolParams(ObjectNode main, String protocolParams) {
        if (protocolParams == null || protocolParams.isBlank()) {
            main.putNull("protocolParams");
            return;
        }
        try {
            main.set("protocolParams", objectMapper.readTree(protocolParams));
        } catch (Exception e) {
            main.put("protocolParams", protocolParams);
        }
    }

    /**
     * 前置步骤解析（编排）：快照存 targetCode（跨环境可移植）→ 回滚时经 resolver 解析回 id；
     * 解析失败直接 40001（不静默丢步骤）。旧快照无 steps 字段 → 空表（向前兼容）。
     */
    private List<StepDto> parseSteps(JsonNode arr, StepTargetResolver resolver) {
        List<StepDto> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            String targetCode = nullable(n, "targetCode");
            Long targetId = null;
            if (targetCode != null && !targetCode.isBlank()) {
                targetId = resolver.resolve(targetCode);
            }
            out.add(new StepDto(intOrNull(n, "seq"), text(n, "stepCode"), targetId,
                    text(n, "failurePolicy"), n.path("enabled").asBoolean(true), targetCode, null));
        }
        return out;
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
