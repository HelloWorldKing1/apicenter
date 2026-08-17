package com.deepx.apicenter.client;

import com.deepx.apicenter.dto.PartnerBOrderRequest;
import com.deepx.apicenter.dto.PartnerResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * PARTNER_B 出站客户端 —— 与 {@link PartnerAClient} 同构，但走 XML（设计文档 §2.1「CLIENT -- HTTP XML --> P_B」）。
 *
 * <p>contentType = "application/xml" 让 RestClient 选用 {@code MappingJackson2XmlHttpMessageConverter}
 * 序列化（依赖 jackson-dataformat-xml，见 pom.xml）。traceparent 透传与鉴权头由 RestClient 统一注入。
 */
@HttpExchange
public interface PartnerBClient {

    /**
     * 推送订单到 PARTNER_B（XML）。
     *
     * @param request PARTNER_B XML 请求体（根元素 OrderPushRequest）
     * @return 通用第三方响应（若 PARTNER_B 返回 XML，标签名需与 {@link com.deepx.apicenter.dto.PartnerResponse}
     *         的 JSON 键对齐或另建 XML 响应 DTO）
     */
    @PostExchange(value = "/v1/order/push", contentType = "application/xml")
    PartnerResponse pushOrder(@RequestBody PartnerBOrderRequest request);
}
