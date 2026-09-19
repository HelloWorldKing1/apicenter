package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.client.OutboundRequestSpec;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.ChainPhase;
import com.deepx.apicenter.engine.UnifiedModel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * BearerTokenAuthAdapter 单测（纯单测，无 Spring / 无 DB）：
 * 出站鉴权头组装——默认 `Authorization: Bearer <token>`、自定义头名、**prefix 置空时不得出现前导空格**
 * （2026-09-18 修复：供应商要求裸 token 时会有人把 prefix 清空，原实现产出 `" token"` → 多数服务端判无效凭证）。
 */
class BearerTokenAuthAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BearerTokenAuthAdapter adapter = new BearerTokenAuthAdapter();

    @Test
    void 默认_Bearer前缀_Authorization头() {
        AdapterContext ctx = ctx("{\"headerName\":\"Authorization\",\"prefix\":\"Bearer\"}", "T-123");

        adapter.process(ctx);

        assertThat(ctx.outbound().headers().getFirst("Authorization")).isEqualTo("Bearer T-123");
    }

    @Test
    void prefix置空_裸token且无前导空格() {
        AdapterContext ctx = ctx("{\"headerName\":\"X-Auth-Token\",\"prefix\":\"\"}", "T-456");

        adapter.process(ctx);

        assertThat(ctx.outbound().headers().getFirst("X-Auth-Token")).isEqualTo("T-456");
        assertThat(ctx.outbound().headers().getFirst("X-Auth-Token")).doesNotStartWith(" ");
    }

    @Test
    void 自定义头名与自定义前缀() {
        AdapterContext ctx = ctx("{\"headerName\":\"X-Api-Key\",\"prefix\":\"Token\"}", "K-789");

        adapter.process(ctx);

        assertThat(ctx.outbound().headers().getFirst("X-Api-Key")).isEqualTo("Token K-789");
        assertThat(ctx.outbound().headers().getFirst("Authorization")).isNull();
    }

    @Test
    void 凭证缺失_不写头且记warning() {
        AdapterContext ctx = ctx("{}", null);

        adapter.process(ctx);

        assertThat(ctx.outbound().headers().isEmpty()).isTrue();
        assertThat(ctx.warnings()).anySatisfy(w -> assertThat(w).contains("出站凭证缺失"));
    }

    /** 非 OUTBOUND_AUTH 阶段直通（不改动任何头） */
    @Test
    void 非出站鉴权阶段_直通() {
        AdapterContext ctx = AdapterContext.create(ChainPhase.ENCODE, UnifiedModel.emptyObject(),
                ifaceMeta(), new AdapterContext.AppMeta("APP", "http://localhost"), new AdapterContext.TraceMeta("t"),
                null, new OutboundRequestSpec());
        ctx.attrs().put("adapterParams", mapper.createObjectNode());
        ctx.attrs().put("outboundCredential", "T-1");

        adapter.process(ctx);

        assertThat(ctx.outbound().headers().isEmpty()).isTrue();
    }

    // ---------- 夹具 ----------

    private AdapterContext ctx(String paramsJson, String credential) {
        AdapterContext ctx = AdapterContext.create(ChainPhase.OUTBOUND_AUTH, UnifiedModel.emptyObject(),
                ifaceMeta(), new AdapterContext.AppMeta("APP", "http://localhost"), new AdapterContext.TraceMeta("t"),
                null, new OutboundRequestSpec());
        try {
            ctx.attrs().put("adapterParams", mapper.readTree(paramsJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (credential != null) {
            ctx.attrs().put("outboundCredential", credential);
        }
        return ctx;
    }

    private AdapterContext.InterfaceMeta ifaceMeta() {
        return new AdapterContext.InterfaceMeta(1L, "IF-T", "OUTBOUND", "POST", "/t", "JSON", "JSON",
                "/up", null, 3000, 4);
    }
}
