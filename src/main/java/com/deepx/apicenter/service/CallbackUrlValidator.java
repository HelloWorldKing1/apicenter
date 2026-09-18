package com.deepx.apicenter.service;

import com.deepx.apicenter.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;

/**
 * 回调地址安全校验（SSRF 防护，M0-03 §4 点名 M3 / 计划 D-M3-2）：
 * 仅 http/https；拒绝内网 / 回环 / 链路本地地址（DNS 解析后判定）。
 * 开关 app.api-center.callback-allow-private 同时作用于管理面保存校验与运行时送达前兜底：
 * 开发 / 测试默认 true（本地 WireMock 回环地址依赖），生产必须 false。
 */
@Component
public class CallbackUrlValidator {

    private final boolean allowPrivate;

    public CallbackUrlValidator(
            @Value("${app.api-center.callback-allow-private:true}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    /**
     * 通用目标地址安全校验（SSRF 防护，M0-03 §4）：
     * 仅 http/https；开关关闭时拒内网 / 回环 / 链路本地地址（DNS 解析后判定）。
     * 同时服务于「入站回调地址」与「应用服务地址（出站 base_url）」——两者共用同一开关，
     * 开发 / 测试默认 true（本地 WireMock 回环地址依赖），生产必须 false。
     */
    public void validateForSave(String url, String label) {
        if (url == null || url.isBlank()) {
            return; // 必填校验由调用方负责
        }
        if (!url.matches("^https?://[^\\s]+$")) {
            throw BizException.fieldInvalid(label + "必须是完整 URL（http/https）：" + url);
        }
        if (!allowPrivate && isPrivate(url)) {
            throw BizException.fieldInvalid(label + "不允许内网 / 回环地址（生产环境需公网地址）：" + url);
        }
    }

    /** 管理面保存校验（回调地址；兼容旧签名）：格式非法 / 内网地址（开关关闭时）直接拒绝 */
    public void validateForSave(String url) {
        validateForSave(url, "回调地址");
    }

    /** 运行时送达前兜底：false 表示不允许送达（引擎转死信，reason 记安全校验失败） */
    public boolean isAllowed(String url) {
        if (url == null || url.isBlank() || !url.matches("^https?://[^\\s]+$")) {
            return false;
        }
        return allowPrivate || !isPrivate(url);
    }

    private boolean isPrivate(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || "localhost".equalsIgnoreCase(host)) {
                return true;
            }
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isSiteLocalAddress();
        } catch (Exception e) {
            return true; // 解析失败按不安全处理（保存时拒绝）
        }
    }
}
