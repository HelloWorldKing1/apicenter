package com.deepx.apicenter.engine;

import com.deepx.apicenter.exception.BizException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌步骤参数（`TokenStepConfig`）单测 —— 2026-09-24 方案 B。
 *
 * <p>重点：① **保存期校验**（非法一律 40001，不静默取默认值）；② **四种 ttlMode** 的换算
 * （腾讯/阿里 STS 返回 ISO8601 到期时刻、OAuth2 普遍返回剩余秒 —— 两种语义都必须支持，
 * 否则"到期"只能靠猜）；③ 越界与"提前刷新必须小于兜底 TTL"这条防呆。
 */
class TokenStepConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 默认值与必填校验() {
        TokenStepConfig c = TokenStepConfig.parse(mapper, "{\"tokenPath\":\"data.access_token\"}");
        assertThat(c.tokenPath()).isEqualTo("data.access_token");
        assertThat(c.ttlMode()).isEqualTo("SECONDS");
        assertThat(c.ttlFallbackSeconds()).isEqualTo(300);
        assertThat(c.refreshAheadSeconds()).isEqualTo(60);

        assertThatThrownBy(() -> TokenStepConfig.parse(mapper, null))
                .isInstanceOf(BizException.class).hasMessageContaining("必须配置 tokenConfig");
        assertThatThrownBy(() -> TokenStepConfig.parse(mapper, "{}"))
                .isInstanceOf(BizException.class).hasMessageContaining("必填 tokenPath");
        assertThatThrownBy(() -> TokenStepConfig.parse(mapper, "not-json"))
                .isInstanceOf(BizException.class).hasMessageContaining("JSON 非法");
        assertThatThrownBy(() -> TokenStepConfig.parse(mapper,
                "{\"tokenPath\":\"t\",\"ttlMode\":\"MINUTES\"}"))
                .isInstanceOf(BizException.class).hasMessageContaining("ttlMode 仅支持");
        assertThatThrownBy(() -> TokenStepConfig.parse(mapper,
                "{\"tokenPath\":\"t\",\"ttlFallbackSeconds\":30,\"refreshAheadSeconds\":60}"))
                .isInstanceOf(BizException.class).hasMessageContaining("必须小于");
        assertThatThrownBy(() -> TokenStepConfig.parse(mapper,
                "{\"tokenPath\":\"t\",\"ttlFallbackSeconds\":-1}"))
                .isInstanceOf(BizException.class).hasMessageContaining("取值 0~");
    }

    @Test
    void ttlMode_SECONDS_剩余秒换算为毫秒() {
        TokenStepConfig c = TokenStepConfig.parse(mapper,
                "{\"tokenPath\":\"t\",\"ttlPath\":\"expires_in\",\"ttlMode\":\"SECONDS\",\"ttlFallbackSeconds\":300}");
        assertThat(c.ttlMillisFrom("120")).isEqualTo(120_000);
        assertThat(c.ttlMillisFrom(120)).isEqualTo(120_000);
        // 缺失/不可解析 ⇒ 兜底 300s
        assertThat(c.ttlMillisFrom(null)).isEqualTo(300_000);
        assertThat(c.ttlMillisFrom("abc")).isEqualTo(300_000);
    }

    @Test
    void ttlMode_ISO8601与EPOCH_按到期时刻换算() {
        TokenStepConfig iso = TokenStepConfig.parse(mapper,
                "{\"tokenPath\":\"t\",\"ttlMode\":\"ISO8601\",\"ttlFallbackSeconds\":1,\"refreshAheadSeconds\":0}");
        String future = Instant.now().plusSeconds(100).toString();
        long ttl = iso.ttlMillisFrom(future);
        assertThat(ttl).isBetween(90_000L, 100_000L);

        TokenStepConfig epochS = TokenStepConfig.parse(mapper,
                "{\"tokenPath\":\"t\",\"ttlMode\":\"EPOCH_S\",\"ttlFallbackSeconds\":1,\"refreshAheadSeconds\":0}");
        long epochTtl = epochS.ttlMillisFrom(String.valueOf(Instant.now().plusSeconds(50).getEpochSecond()));
        assertThat(epochTtl).isBetween(40_000L, 50_000L);

        TokenStepConfig epochMs = TokenStepConfig.parse(mapper,
                "{\"tokenPath\":\"t\",\"ttlMode\":\"EPOCH_MS\",\"ttlFallbackSeconds\":1,\"refreshAheadSeconds\":0}");
        assertThat(epochMs.ttlMillisFrom(String.valueOf(Instant.now().plusSeconds(30).toEpochMilli())))
                .isBetween(20_000L, 30_000L);
    }

    @Test
    void 已过期的时刻_换算为0_且被clamp到24小时上限() {
        TokenStepConfig c = TokenStepConfig.parse(mapper,
                "{\"tokenPath\":\"t\",\"ttlMode\":\"ISO8601\",\"ttlFallbackSeconds\":1,\"refreshAheadSeconds\":0}");
        assertThat(c.ttlMillisFrom(Instant.now().minusSeconds(10).toString())).isZero();

        TokenStepConfig huge = TokenStepConfig.parse(mapper,
                "{\"tokenPath\":\"t\",\"ttlMode\":\"SECONDS\",\"ttlFallbackSeconds\":300}");
        assertThat(huge.ttlMillisFrom("99999999")).isEqualTo(TokenStepConfig.MAX_TTL_SECONDS * 1000L);
    }

    @Test
    void ttlFallbackSeconds为0且无ttlPath_视为不缓存() {
        TokenStepConfig c = TokenStepConfig.parse(mapper,
                "{\"tokenPath\":\"t\",\"ttlFallbackSeconds\":0,\"refreshAheadSeconds\":0}");
        assertThat(c.cacheDisabled()).isTrue();
        assertThat(c.ttlMillisFrom(null)).isZero();
    }
}
