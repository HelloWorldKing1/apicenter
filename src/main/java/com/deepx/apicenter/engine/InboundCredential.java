package com.deepx.apicenter.engine;

/**
 * 入站鉴权**候选凭证**（2026-09-24 v1.2 / D-CA-20）。
 *
 * <p>闸门把凭证池里可用的凭证（`ACTIVE` + 未过期 `ROTATING`）解密后注入适配器 —— 注入的不再只是明文串，
 * 而是「**id + 明文 + 备注 + 指纹**」：适配器命中哪一条就把 id 回写上下文，审计据此落
 * `credential_label` / `credential_fingerprint`。
 *
 * <p><b>为什么必须带上 id</b>：共享凭证模型下「身份」是自报的（可伪造），**「命中哪把密钥」才是不可伪造的事实** ——
 * 它同时是可归因（这把密钥是谁的）、可吊销（删哪一行）、可告警（某把密钥失败激增 = 疑似泄露/被扫）的抓手。
 *
 * @param id          凭证行 id（{@link #NO_ID} = 未知，例如测试或存量注入形态）
 * @param plaintext   解密后的明文（**只在内存中流转，绝不落日志/审计**）
 * @param label       人工备注（「发给谁 / 何时」，可空）
 * @param fingerprint 指纹（明文尾 4）
 */
public record InboundCredential(long id, String plaintext, String label, String fingerprint) {

    /** 未知 id（测试注入明文串时的兜底形态） */
    public static final long NO_ID = -1L;

    /** 兼容形态：只有明文（无归因信息） */
    public static InboundCredential ofPlaintext(String plaintext) {
        return new InboundCredential(NO_ID, plaintext, null, null);
    }
}
