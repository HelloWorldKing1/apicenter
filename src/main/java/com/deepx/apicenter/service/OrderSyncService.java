package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.ErpOrderResponse;
import com.deepx.apicenter.dto.OrderDto;
import com.deepx.apicenter.dto.PartnerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Flow A 出站编排 —— ERP 推单 → 落库 → 映射 → 调用第三方 → 反向映射返回（设计文档 §3 / §6）。
 *
 * <p>状态机载体是 {@code integration_request.status}（INIT→MAPPING→…→SUCCESS/DEAD_LETTER/UNKNOWN）：
 * <ul>
 *   <li>成功 → SUCCESS</li>
 *   <li>400 等 4xx 业务错误 → 写 dead_letter → DEAD_LETTER（不重试）</li>
 *   <li>5xx/429 由 {@link PartnerInvoker} 的 @Retryable 短重试；重试耗尽且仍失败 → 转补偿（见 {@code CompensationWorker}）</li>
 *   <li>读超时 → UNKNOWN → reconcile 对账</li>
 * </ul>
 */
@Service
public class OrderSyncService {

    private static final Logger log = LoggerFactory.getLogger(OrderSyncService.class);

    /** 出站请求状态机（设计文档 §6.1）。 */
    public enum RequestStatus {
        INIT, MAPPING, SENDING, RETRYING, COMPENSATING, SUCCESS, DEAD_LETTER, UNKNOWN
    }

    private final PartnerInvoker partnerInvoker;
    private final JdbcTemplate jdbcTemplate;

    public OrderSyncService(PartnerInvoker partnerInvoker, JdbcTemplate jdbcTemplate) {
        this.partnerInvoker = partnerInvoker;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 推单主流程（Flow A 步骤 1→7）。
     */
    public ErpOrderResponse pushOrder(OrderDto order) {
        // 1) 落库 INIT（outbox 语义：先记后发，为补偿提供依据）
        long requestId = insertRequest(order, RequestStatus.INIT);
        updateStatus(requestId, RequestStatus.MAPPING);

        try {
            // 2) 映射 + 调用（@Retryable 在 PartnerInvoker 内生效；400 业务错误直接抛出）
            PartnerResponse resp = partnerInvoker.invoke(order);

            // 3) 成功：SUCCESS + 写调用日志 + 反向组装 ERP 响应
            updateStatus(requestId, RequestStatus.SUCCESS);
            insertCallLog(requestId, 200, resp);
            return ErpOrderResponse.success(resp.orderNo(), resp.status(), order.totalAmount());

        } catch (HttpClientErrorException e) {
            // 4) 4xx 业务错误（非 429，429 已被 @Retryable 拦截）→ 死信，不重试
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw e; // 429 交给重试切面
            }
            long deadLetterId = insertDeadLetter(requestId, order, e);
            updateStatus(requestId, RequestStatus.DEAD_LETTER);
            log.warn("订单 {} 进死信，requestId={}, reason={}", order.orderId(), requestId, e.getResponseBodyAsString());
            return ErpOrderResponse.failure(e.getStatusCode().value(), e.getResponseBodyAsString(), deadLetterId);

        } catch (ResourceAccessException e) {
            // 5) 读超时/连接异常（重试耗尽后）：结果不确定 → UNKNOWN → 对账
            updateStatus(requestId, RequestStatus.UNKNOWN);
            return reconcile(requestId, order);
        }
    }

    /**
     * UNKNOWN 对账：调第三方查询接口确认最终结果（设计文档 §6.5 / §10.1 /orders/query）。
     * 生产应调 {@code /v1/order/query}；确认成功→SUCCESS，确认失败→DEAD_LETTER，
     * 仍不确定→COMPENSATING。此处示例按「已成功」处理并交补偿 worker 兜底。
     */
    private ErpOrderResponse reconcile(long requestId, OrderDto order) {
        updateStatus(requestId, RequestStatus.SUCCESS);
        return ErpOrderResponse.success(order.orderId(), "RECONCILED", order.totalAmount());
    }

    /**
     * /api/orders/query 对账查询入口（供 OrderController 调用）。
     */
    public ErpOrderResponse queryOrderStatus(OrderDto order) {
        // 示例：直接返回占位结果；生产应查 integration_request 状态并落日志
        return ErpOrderResponse.success(order.orderId(), "QUERIED", order.totalAmount());
    }

    // ==================== JdbcTemplate 落库（示例级；生产可换 ORM / 分库分表） ====================

    private long insertRequest(OrderDto order, RequestStatus status) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO integration_request
                      (biz_id, biz_type, channel_code, endpoint, request_payload, status,
                       attempt_count, max_attempts, trace_id, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setString(1, order.orderId());
            ps.setString(2, order.orderType());
            ps.setString(3, "PARTNER_A");
            ps.setString(4, "/v1/order/push");
            ps.setString(5, order.toString());            // 示例简化：未做完整 JSON 序列化
            ps.setString(6, status.name());
            ps.setInt(7, 0);
            ps.setInt(8, 5);                              // 对齐 max-attempts
            ps.setString(9, "trace-" + order.orderId());  // 生产：注入 Tracer.currentTraceId()
            ps.setTimestamp(10, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void updateStatus(long requestId, RequestStatus status) {
        jdbcTemplate.update("UPDATE integration_request SET status = ? WHERE id = ?", status.name(), requestId);
    }

    private long insertDeadLetter(long requestId, OrderDto order, HttpClientErrorException e) {
        jdbcTemplate.update("""
                INSERT INTO dead_letter (request_id, channel_code, biz_id, reason, payload, status, created_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                requestId, "PARTNER_A", order.orderId(), e.getResponseBodyAsString(), order.toString(),
                "PENDING", Timestamp.valueOf(LocalDateTime.now()));
        return requestId; // 示例简化：用 requestId 充当 deadLetterId
    }

    private void insertCallLog(long requestId, int statusCode, PartnerResponse resp) {
        jdbcTemplate.update("""
                INSERT INTO integration_call_log
                  (trace_id, span_id, direction, channel_code, url, method, status_code,
                   latency_ms, req_headers, req_body, resp_body, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                "trace-" + requestId, "span-1", "OUT", "PARTNER_A", "/v1/order/push", "POST",
                statusCode, 0, "{}", "", resp == null ? "" : resp.toString(), Timestamp.valueOf(LocalDateTime.now()));
    }
}
