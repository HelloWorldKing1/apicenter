package com.deepx.apicenter.client;

import com.deepx.apicenter.dto.PartnerAOrderRequest;
import com.deepx.apicenter.dto.PartnerResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * PARTNER_A 出站客户端 —— Spring Framework 7 声明式 HTTP 接口（{@code @HttpExchange}，设计文档 §2.2/§3）。
 *
 * <p>由 {@code RestClientConfig} 通过 {@code HttpServiceProxyFactory} 生成代理 Bean：
 * 每次调用自动注入 baseUrl / 鉴权头 / traceparent（OpenTelemetry），无需手写 HTTP 模板。
 */
@HttpExchange
public interface PartnerAClient {

    /**
     * 推送订单到 PARTNER_A（JSON）。
     *
     * <p>失败行为由调用方 {@code OrderSyncService.invokePartner} 的 {@code @Retryable} 处理：
     * 5xx/429/IO 异常抛出后重试；400 等业务错误由 HttpClientErrorException 暴露给编排层。
     *
     * @param request PARTNER_A JSON 请求体
     * @return 通用第三方响应（code/message/orderNo/status）
     */
    @PostExchange(value = "/v1/order/push", contentType = "application/json")
    PartnerResponse pushOrder(@RequestBody PartnerAOrderRequest request);
}
