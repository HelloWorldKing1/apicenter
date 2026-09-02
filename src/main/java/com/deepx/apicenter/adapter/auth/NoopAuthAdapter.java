package com.deepx.apicenter.adapter.auth;

import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.AdapterType;
import org.springframework.stereotype.Component;

/**
 * 无鉴权（M0-01 D4 平台默认兜底；内网/演示场景）：直通，不附加任何凭证。
 */
@Component("NoopAuthAdapter")
public class NoopAuthAdapter implements Adapter {

    @Override
    public AdapterType type() {
        return AdapterType.AUTH;
    }

    @Override
    public AdapterContext process(AdapterContext ctx) {
        return ctx;
    }
}
