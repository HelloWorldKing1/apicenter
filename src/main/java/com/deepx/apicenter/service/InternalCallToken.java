package com.deepx.apicenter.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * **内部调用令牌**（2026-09-23，入站鉴权 B2 / D-CA-17 + §3.4）。
 *
 * <p>为什么需要：闸门在 `GatewayController` 内 ⇒ **平台自己发出的调用也会过闸门**。最典型的是管理面
 * 「模拟回调」`/test-callback` —— 它**自调网关**（`POST http://localhost:{port}{platformPath}`），
 * `client-auth.mode=ENFORCED` 一上线就会被自家闸门拒掉（该调试工具直接失效）。
 *
 * <p>方案要点（评审拍板的取舍）：
 * <ul>
 *   <li>令牌**启动时随机生成、仅存内存**（进程内比对）——自调是**本实例到自己**，多实例各自独立即可，
 *       因此**不需要配置项**，也避免把密钥写进配置文件 / 仓库；</li>
 *   <li>比对用**常量时间比较**，且**令牌为空一律不豁免**（禁止"空令牌放行"被误实现）；</li>
 *   <li>**不用**"来源 IP 是回环"作豁免判据 —— 反代 / 容器 / K8s 下真实 IP 取法复杂（设计方案 §11），
 *       按 IP 放行等于给外部流量开后门。</li>
 * </ul>
 *
 * <p>配置项 `app.api-center.client-auth.internal-token` **仅供测试显式覆盖**；默认走本类随机生成。
 */
@Component
public class InternalCallToken {

    /** 内部调用头名（管理面自调时携带；闸门步骤 ⓪ 先判） */
    public static final String HEADER = "X-Internal-Token";

    private final String token;

    public InternalCallToken(com.deepx.apicenter.config.ClientAuthProperties props) {
        String configured = props.internalTokenOrNull();
        if (configured != null) {
            this.token = configured;   // 测试显式指定（含"置空 ⇒ 不豁免"的用例）
        } else {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            this.token = HexFormat.of().formatHex(b);
        }
    }

    /** 令牌明文（仅用于**由平台自己**发起的内部调用带头，如管理面「模拟回调」自调；不得对外泄露/记日志） */
    public String value() {
        return token;
    }

    /** 是否为本平台的内部调用（空值/空令牌一律不通过） */
    public boolean matches(String candidate) {
        if (candidate == null || candidate.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(candidate.trim().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
