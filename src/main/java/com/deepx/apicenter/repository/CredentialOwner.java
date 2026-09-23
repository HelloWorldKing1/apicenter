package com.deepx.apicenter.repository;

import java.util.List;

/**
 * 凭证「属主」枚举（2026-09-23，入站鉴权 B1 / D-CA-15 方案 A）。
 *
 * <p>把 M0-04 的凭证机制（`ACTIVE`/`ROTATING`/`RETIRED` + AES-GCM + 遮显 + 轮换并存）**参数化到两类属主**，
 * 避免为调用方复制一份 ~150 行的凭证服务（方案 B）。
 *
 * <p>**语义入口仍分开**：`CredentialService`（应用 = 供应商：出站签名 / 回调验签）与
 * `ClientCredentialService`（调用方 = 平台客户：入站鉴权）各自做「属主存在性」检查，再委托 `CredentialStore` 执行机制
 * —— 这样 `appId` 语义**不会**漂移到调用方（D-CA-1 / §7.1 的顾虑）。
 *
 * <p>⚠️ 表名 / 列名来自本枚举的**常量**（绝非用户输入）⇒ 拼进 SQL 安全；`kinds()` 用 `List` 保持声明顺序，
 * 以便错误文案稳定（`OUTBOUND / CALLBACK`，不因 `Set` 的迭代顺序漂移）。
 */
public enum CredentialOwner {

    /** 应用（供应商）：`OUTBOUND` 出站签名 / `CALLBACK` 回调验签 */
    APP("app_credential", "app_id", List.of("OUTBOUND", "CALLBACK"),
            "应用", "该类型已无有效凭证，出站签名/回调验签将不可用，请立即补发"),

    /** 调用方（平台客户）：入站鉴权方式对应的凭证类型 */
    CLIENT("client_credential", "client_id", List.of("API_KEY", "HMAC_SECRET", "BEARER_TOKEN", "BASIC"),
            "调用方", "该类型已无有效凭证，调用方入站鉴权将不可用，请立即补发");

    private final String table;
    private final String column;
    private final List<String> kinds;
    private final String displayName;
    private final String retireWarning;

    CredentialOwner(String table, String column, List<String> kinds, String displayName, String retireWarning) {
        this.table = table;
        this.column = column;
        this.kinds = kinds;
        this.displayName = displayName;
        this.retireWarning = retireWarning;
    }

    /** 错误文案用的主体名（应用 / 调用方）；无参构造留给旧签名兼容 —— 见下方静态工厂 */
    public String displayName() {
        return displayName;
    }

    /** 凭证耗尽时的告警文案（方向措辞不同：应用=出站签名/回调验签；调用方=入站鉴权） */
    public String retireWarning() {
        return retireWarning;
    }

    public String table() {
        return table;
    }

    /** 属主外键列名（`app_id` / `client_id`）——仓储按此列查询 */
    public String column() {
        return column;
    }

    public List<String> kinds() {
        return kinds;
    }

    public boolean supportsKind(String kind) {
        return kind != null && kinds.contains(kind);
    }

    /** 错误文案用的类型列表（保持声明顺序） */
    public String kindsLabel() {
        return String.join(" / ", kinds);
    }
}
