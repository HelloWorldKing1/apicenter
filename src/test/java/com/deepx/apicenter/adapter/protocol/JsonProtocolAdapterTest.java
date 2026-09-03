package com.deepx.apicenter.adapter.protocol;

import com.deepx.apicenter.client.OutboundRequestSpec;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.ChainPhase;
import com.deepx.apicenter.engine.UnifiedModel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON 协议适配器纯单测：类型标注边界（M0-01 §1）。
 * 重点覆盖中危 #6：超出 long 范围的大整数必须转 DECIMAL，不得静默截断。
 */
class JsonProtocolAdapterTest {

    private final JsonProtocolAdapter adapter = new JsonProtocolAdapter(new ObjectMapper());

    private AdapterContext decode(String json) {
        AdapterContext ctx = AdapterContext.create(
                ChainPhase.DECODE, UnifiedModel.emptyObject(),
                new AdapterContext.InterfaceMeta(0, "T", "OUTBOUND", "POST", "/t",
                        "JSON", "JSON", null, null, 3000, 4),
                new AdapterContext.AppMeta("A", "http://x"),
                new AdapterContext.TraceMeta("t"), null, new OutboundRequestSpec());
        ctx.attrs().put("rawBody", json.getBytes(StandardCharsets.UTF_8));
        return adapter.process(ctx);
    }

    @Test
    void 超long大整数转DECIMAL不截断() {
        String big = "123456789012345678901234567890"; // 30 位，远超 long
        UnifiedModel.ScalarNode node = (UnifiedModel.ScalarNode) decode("{\"big\":" + big + "}")
                .payload().get("big").orElseThrow();
        assertThat(node.type()).isEqualTo(UnifiedModel.ScalarType.DECIMAL);
        assertThat(node.value()).isEqualTo(new java.math.BigDecimal(big));
    }

    @Test
    void long范围内整数保持INT() {
        UnifiedModel.ScalarNode node = (UnifiedModel.ScalarNode) decode("{\"n\":822}")
                .payload().get("n").orElseThrow();
        assertThat(node.type()).isEqualTo(UnifiedModel.ScalarType.INT);
        assertThat(node.value()).isEqualTo(822L);
    }

    @Test
    void 小数转DECIMAL() {
        UnifiedModel.ScalarNode node = (UnifiedModel.ScalarNode) decode("{\"g\":70283.55}")
                .payload().get("g").orElseThrow();
        assertThat(node.type()).isEqualTo(UnifiedModel.ScalarType.DECIMAL);
        assertThat(node.value()).isEqualTo(new java.math.BigDecimal("70283.55"));
    }
}
