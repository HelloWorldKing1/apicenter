package com.deepx.apicenter.service;

import com.deepx.apicenter.client.PartnerAClient;
import com.deepx.apicenter.client.PartnerBClient;
import com.deepx.apicenter.dto.OrderDto;
import com.deepx.apicenter.dto.PartnerAOrderRequest;
import com.deepx.apicenter.dto.PartnerResponse;
import com.deepx.apicenter.mapper.OrderMapper;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException.TooManyRequests;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.TimeUnit;

/**
 * 第三方调用器 —— 把 {@code @Retryable} 独立成一个 Bean。
 *
 * <p><b>为什么独立成 Bean？</b> Spring AOP（@Retryable 底层实现）对「本类自调用」不生效；
 * 若把带注解的方法放在 {@link OrderSyncService} 内部再同类调用，代理不会触发重试。
 * 单独抽出后，OrderSyncService 通过注入调用，重试切面才能拦截（设计文档 §6.3）。
 */
@Service
public class PartnerInvoker {

    private final OrderMapper orderMapper;
    private final PartnerAClient partnerAClient;
    private final PartnerBClient partnerBClient;

    public PartnerInvoker(OrderMapper orderMapper, PartnerAClient partnerAClient,
                          PartnerBClient partnerBClient) {
        this.orderMapper = orderMapper;
        this.partnerAClient = partnerAClient;
        this.partnerBClient = partnerBClient;
    }

    /**
     * 映射并调用第三方 —— 指数退避短重试。
     *
     * <p>重试策略（对齐 application.yaml max-attempts=5 / 设计文档 §6.3）：
     * <ul>
     *   <li>5xx / 429 / 连接&读超时（ResourceAccessException）→ 重试</li>
     *   <li>退避 delay=200ms ×2，上限 maxDelay=2000ms，总尝试 5 次</li>
     * </ul>
     * 重试耗尽后异常继续向上抛，由 {@link OrderSyncService} 决定转补偿 / 死信 / UNKNOWN。
     *
     * <p>属性说明（Spring 7 原生 resilience，已通过 javap 实证 spring-context-7.0.8）：
     * <ul>
     *   <li>{@code includes}：触发重试的异常（5xx / 429 / IO&读超时）</li>
     *   <li>{@code maxRetries}：重试次数（非总尝试数）；max-attempts=5 → 首次 + 4 次重试</li>
     *   <li>{@code delay / multiplier / maxDelay / timeUnit}：指数退避 200 → 400 → 800 → 1600ms</li>
     * </ul>
     *
     * @param order 统一订单
     * @return 第三方通用响应
     */
    @Retryable(
            includes = {HttpServerErrorException.class, TooManyRequests.class, ResourceAccessException.class},
            maxRetries = 4,          // app.integration.max-attempts=5 → 首次 + 4 次重试
            delay = 200,             // 首次重试退避 200ms
            multiplier = 2,          // 指数退避 ×2
            maxDelay = 2000,         // 退避上限 2000ms
            timeUnit = TimeUnit.MILLISECONDS)
    public PartnerResponse invoke(OrderDto order) {
        // 示例按渠道路由：默认 PARTNER_A（JSON）；生产可按 orderType/渠道配置同时路由 PARTNER_B（XML）
        PartnerAOrderRequest request = orderMapper.toPartnerA(order);
        return partnerAClient.pushOrder(request);
    }
}
