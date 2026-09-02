package com.deepx.apicenter.adapter.protocol;

import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.AdapterType;
import com.deepx.apicenter.engine.ChainPhase;
import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.exception.BizException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 协议编解码（M0-01 D5：首期平台默认参数——保留字段名原样 / 空值包含 / 整数 INT、小数 DECIMAL）。
 * DECODE：原始请求体字节（ctx.attrs("rawBody")）→ UnifiedModel（类型标注）；
 * ENCODE：UnifiedModel → 出站 JSON 字节（写 ctx.outbound.body）。
 * 解码失败 → 40002 报文格式非法（链失败不污染状态机，M0-01 §6）。
 */
@Component("JsonProtocolAdapter")
public class JsonProtocolAdapter implements Adapter {

    private final ObjectMapper objectMapper;

    public JsonProtocolAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public AdapterType type() {
        return AdapterType.PROTOCOL;
    }

    @Override
    public AdapterContext process(AdapterContext ctx) {
        return switch (ctx.phase()) {
            case DECODE -> decode(ctx);
            case ENCODE -> encode(ctx);
            default -> ctx;
        };
    }

    private AdapterContext decode(AdapterContext ctx) {
        byte[] raw = (byte[]) ctx.attrs().get("rawBody");
        if (raw == null || raw.length == 0) {
            ctx.payload().root(UnifiedModel.ObjectNode.of());
            return ctx;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            ctx.payload().root(toUnified(node));
            return ctx;
        } catch (Exception e) {
            throw new BizException(40002, "报文格式非法：" + e.getMessage());
        }
    }

    private AdapterContext encode(AdapterContext ctx) {
        try {
            JsonNode node = fromUnified(ctx.payload().root());
            ctx.outbound().body(objectMapper.writeValueAsBytes(node));
            ctx.outbound().header("Content-Type", "application/json");
            ctx.outbound().header("Accept", "application/json");
            return ctx;
        } catch (Exception e) {
            throw new BizException(50000, "报文编码失败：" + e.getMessage());
        }
    }

    // ---------- JsonNode ⇄ UnifiedModel（类型标注：整数 INT / 小数 DECIMAL，M0-01 §1） ----------

    private UnifiedModel.UNode toUnified(JsonNode node) {
        if (node == null || node.isNull()) {
            return UnifiedModel.ScalarNode.nullNode();
        }
        if (node.isObject()) {
            LinkedHashMap<String, UnifiedModel.UNode> fields = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> fields.put(e.getKey(), toUnified(e.getValue())));
            return new UnifiedModel.ObjectNode(fields, Map.of());
        }
        if (node.isArray()) {
            List<UnifiedModel.UNode> items = new ArrayList<>();
            node.forEach(n -> items.add(toUnified(n)));
            return new UnifiedModel.ArrayNode(items);
        }
        if (node.isIntegralNumber()) {
            return UnifiedModel.ScalarNode.num(node.longValue());
        }
        if (node.isFloatingPointNumber() || node.isBigDecimal()) {
            return UnifiedModel.ScalarNode.decimal(node.decimalValue());
        }
        if (node.isBoolean()) {
            return UnifiedModel.ScalarNode.bool(node.booleanValue());
        }
        return UnifiedModel.ScalarNode.str(node.asText());
    }

    private JsonNode fromUnified(UnifiedModel.UNode node) {
        return switch (node) {
            case UnifiedModel.ObjectNode obj -> {
                ObjectNode out = objectMapper.createObjectNode();
                obj.fields().forEach((k, v) -> out.set(k, fromUnified(v)));
                yield out;
            }
            case UnifiedModel.ArrayNode arr -> {
                ArrayNode out = objectMapper.createArrayNode();
                arr.items().forEach(v -> out.add(fromUnified(v)));
                yield out;
            }
            case UnifiedModel.ScalarNode s -> switch (s.type()) {
                case STRING -> objectMapper.getNodeFactory().textNode((String) s.value());
                case INT -> objectMapper.getNodeFactory().numberNode((Long) s.value());
                case DECIMAL -> objectMapper.getNodeFactory().numberNode((BigDecimal) s.value());
                case BOOLEAN -> objectMapper.getNodeFactory().booleanNode((Boolean) s.value());
                case NULL -> objectMapper.getNodeFactory().nullNode();
            };
            default -> throw new IllegalStateException("未知节点类型");
        };
    }
}
