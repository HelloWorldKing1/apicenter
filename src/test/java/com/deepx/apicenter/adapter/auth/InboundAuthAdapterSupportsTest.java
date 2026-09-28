package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.Adapter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InboundAuthAdapter#supports} 单测（2026-09-25）。
 *
 * <p>它是**保存期校验**（调用方档案的鉴权适配器 / 平台默认方式）与**闸门运行期**判定的同一个判据：
 * 「Spring 容器里这个 impl 的 Bean 是不是 `InboundAuthAdapter`」——避免出现
 * 「配得上、跑不通（40108）」。真实案例：验收 S3.12 把「HMAC **回调**验签」（`HmacCallbackVerifyAdapter`）
 * 当成了调用方方式，直到发请求才报「实现不支持入站鉴权」。
 */
class InboundAuthAdapterSupportsTest {

    /** 最小替身：只关心「是不是 InboundAuthAdapter」（判据看类型，不看行为） */
    private static class FakeAdapter implements Adapter {
        @Override
        public com.deepx.apicenter.engine.AdapterType type() {
            return com.deepx.apicenter.engine.AdapterType.AUTH;
        }

        @Override
        public com.deepx.apicenter.engine.AdapterContext process(com.deepx.apicenter.engine.AdapterContext ctx) {
            return ctx;
        }
    }

    private static class FakeInboundAdapter extends FakeAdapter implements InboundAuthAdapter {
        @Override
        public String method() {
            return "API_KEY";
        }

        @Override
        public String credentialKind() {
            return "API_KEY";
        }
    }

    private final Map<String, Adapter> beans = Map.of(
            "ClientApiKeyVerifyAdapter", new FakeInboundAdapter(),
            "ClientHmacVerifyAdapter", new FakeInboundAdapter(),
            "HmacCallbackVerifyAdapter", new FakeAdapter(),
            "NoopAuthAdapter", new FakeAdapter());

    @Test
    void 入站鉴权的四个实现_判定为支持() {
        assertThat(InboundAuthAdapter.supports("ClientApiKeyVerifyAdapter", beans)).isTrue();
        assertThat(InboundAuthAdapter.supports("ClientHmacVerifyAdapter", beans)).isTrue();
    }

    @Test
    void 回调验签与Noop实现_判定为不支持() {
        // 这两个是真实踩坑来源：选中后闸门必然 40108（回调验签只服务入站回调；Noop 不再是入站放行语义）
        assertThat(InboundAuthAdapter.supports("HmacCallbackVerifyAdapter", beans)).isFalse();
        assertThat(InboundAuthAdapter.supports("NoopAuthAdapter", beans)).isFalse();
    }

    @Test
    void 未注册的实现_空值_空容器_一律不支持() {
        assertThat(InboundAuthAdapter.supports("NotRegisteredAdapter", beans)).isFalse();
        assertThat(InboundAuthAdapter.supports(null, beans)).isFalse();
        assertThat(InboundAuthAdapter.supports("  ", beans)).isFalse();
        assertThat(InboundAuthAdapter.supports("ClientHmacVerifyAdapter", null)).isFalse();
        assertThat(InboundAuthAdapter.supports(null, null)).isFalse();
    }

    @Test
    void 前后空白容忍() {
        assertThat(InboundAuthAdapter.supports("  ClientHmacVerifyAdapter  ", beans)).isTrue();
    }
}
