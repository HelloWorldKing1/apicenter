package com.deepx.apicenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 凭证加解密配置（app.api-center.crypto）。
 * key：AES-256 密钥的 base64 编码（32 字节）。生产环境用环境变量 APICENTER_CRYPTO_KEY 注入。
 */
@ConfigurationProperties(prefix = "app.api-center.crypto")
public record CryptoProperties(String key) {
}
