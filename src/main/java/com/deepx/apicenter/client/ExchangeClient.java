package com.deepx.apicenter.client;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.Map;

/**
 * 通用声明式客户端（M0-03 定稿 C1）：单一接口、按协议维度（Content-Type/Accept 由 headers 参数传入），
 * 不按渠道写死。URL / 方法 / Header / Body 全参数化，由 OutboundRequestBuilder 组装。
 * 注意：url 模板变量必须显式命名（@PathVariable("url")）——编译默认不带 -parameters，
 * 参数名擦除会导致 {url} 模板变量匹配失败（URI with undefined scheme）。
 * 4xx 非 429 不在此抛异常（返回 ResponseEntity 由引擎分类）；5xx/429/连接异常由 UpstreamInvoker 判定后抛（触发 @Retryable）。
 */
public interface ExchangeClient {

    @HttpExchange(method = "POST", url = "{url}", contentType = "application/octet-stream")
    ResponseEntity<byte[]> post(@PathVariable("url") String url,
                                @RequestHeader("headers") Map<String, String> headers,
                                @RequestBody("body") byte[] body);

    @HttpExchange(method = "PUT", url = "{url}", contentType = "application/octet-stream")
    ResponseEntity<byte[]> put(@PathVariable("url") String url,
                               @RequestHeader("headers") Map<String, String> headers,
                               @RequestBody("body") byte[] body);

    @HttpExchange(method = "GET", url = "{url}", contentType = "application/octet-stream")
    ResponseEntity<byte[]> get(@PathVariable("url") String url,
                               @RequestHeader("headers") Map<String, String> headers);

    @HttpExchange(method = "DELETE", url = "{url}", contentType = "application/octet-stream")
    ResponseEntity<byte[]> delete(@PathVariable("url") String url,
                                  @RequestHeader("headers") Map<String, String> headers);
}
