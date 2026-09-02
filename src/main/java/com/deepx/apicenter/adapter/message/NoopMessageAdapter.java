package com.deepx.apicenter.adapter.message;

import com.deepx.apicenter.engine.Adapter;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.AdapterType;
import org.springframework.stereotype.Component;

/**
 * 直通报文适配器（M0-01 D4 平台默认兜底）：不做任何结构转换，
 * 成败判断回退 HTTP 状态码（信封解析留给显式绑定 EnvelopeMessageAdapter 的接口）。
 */
@Component("NoopMessageAdapter")
public class NoopMessageAdapter implements Adapter {

    @Override
    public AdapterType type() {
        return AdapterType.MESSAGE;
    }

    @Override
    public AdapterContext process(AdapterContext ctx) {
        return ctx;
    }
}
