package com.deepx.apicenter.adapter.message;

import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.AdapterType;
import com.deepx.apicenter.engine.UnifiedModel;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 信封报文适配器（设计 §5.5）：请求方向直通；响应方向由 OutboundEngine 调
 * {@link #adaptResponse} —— 剥上游信封（envelope，如 data）+ 读上游状态码
 * （codeField / successValue）判断业务成败 + 错误码映射（codeMappings）+ 消息透传（messageField）。
 */
@Component("EnvelopeMessageAdapter")
public class EnvelopeMessageAdapter implements Adapter {

    @Override
    public AdapterType type() {
        return AdapterType.MESSAGE;
    }

    @Override
    public AdapterContext process(AdapterContext ctx) {
        // 请求方向（M2）：不做包裹转换，直通；响应方向的信封处理见 adaptResponse
        return ctx;
    }

    /**
     * 响应方向信封适配：剥 envelope 取业务数据、读 codeField 判成败。
     * 未配置 envelope（空）→ 整个响应体即业务数据；未配置 codeField → 成败由 HTTP 状态码决定（true）。
     */
    public EnvelopeResult adaptResponse(UnifiedModel respModel, JsonNode params) {
        String envelope = text(params, "envelope", "");
        String codeField = text(params, "codeField", "");
        String successValue = text(params, "successValue", "");
        String messageField = text(params, "messageField", "");
        Map<String, String> codeMappings = parseCodeMappings(params);
        String defaultErrorCode = text(params, "defaultErrorCode", "50201");

        UnifiedModel.UNode bizData;
        if (!envelope.isBlank()) {
            Optional<UnifiedModel.UNode> data = respModel.get(envelope);
            bizData = data.orElse(respModel.root());
        } else {
            bizData = respModel.root();
        }

        boolean success = true;
        String code = null;
        String msg = null;
        if (!codeField.isBlank()) {
            Optional<UnifiedModel.UNode> codeNode = respModel.get(codeField);
            code = codeNode.filter(n -> n instanceof UnifiedModel.ScalarNode)
                    .map(n -> String.valueOf(((UnifiedModel.ScalarNode) n).value()))
                    .orElse(null);
            success = successValue.equals(code);
            if (!success && codeMappings.containsKey(code)) {
                code = codeMappings.get(code);
            } else if (!success && code == null) {
                code = defaultErrorCode;
            }
        }
        if (!messageField.isBlank()) {
            Optional<UnifiedModel.UNode> msgNode = respModel.get(messageField);
            msg = msgNode.filter(n -> n instanceof UnifiedModel.ScalarNode)
                    .map(n -> String.valueOf(((UnifiedModel.ScalarNode) n).value()))
                    .orElse(null);
        }
        return new EnvelopeResult(bizData, success, code, msg);
    }

    /** 信封适配结果：业务数据 + 业务成败 + 映射后的平台码 + 上游消息 */
    public record EnvelopeResult(UnifiedModel.UNode bizData, boolean success, String code, String msg) {
    }

    private String text(JsonNode params, String key, String def) {
        if (params == null || !params.has(key) || params.get(key).isNull() || params.get(key).asText().isBlank()) {
            return def;
        }
        return params.get(key).asText();
    }

    /** codeMappings 参数：上游错误码 → 平台错误码（M0-01 §5.8；M2 以 JSON 对象或逗号对承载） */
    private Map<String, String> parseCodeMappings(JsonNode params) {
        Map<String, String> mappings = new LinkedHashMap<>();
        if (params == null || !params.has("codeMappings") || params.get("codeMappings").isNull()) {
            return mappings;
        }
        JsonNode node = params.get("codeMappings");
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> mappings.put(e.getKey(), e.getValue().asText()));
        } else if (node.isTextual()) {
            // 兼容逗号对格式：FROM→TO, FROM2→TO2
            for (String pair : node.asText().split(",")) {
                String[] kv = pair.trim().split("→");
                if (kv.length == 2) {
                    mappings.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return mappings;
    }
}
