package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.CredentialRow;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

/**
 * 凭证「属主」枚举（2026-09-23 入站鉴权 B1 / D-CA-15 方案 A；**2026-09-24 v1.2 扩为四级属主**）。
 *
 * <p>把 M0-04 的凭证机制（`ACTIVE`/`ROTATING`/`RETIRED` + AES-GCM + 遮显 + 轮换并存）**参数化到多类属主**，
 * 避免为每类属主复制一份 ~150 行的凭证服务（方案 B）。
 *
 * <p>**两类存储形态**（v1.2）：
 * <ul>
 *   <li><b>应用形态</b>（{@link #APP}）：`app_credential`，属主列表 `app_id`；</li>
 *   <li><b>凭证池形态</b>（{@link #CLIENT} / {@link #INTERFACE} / {@link #PLATFORM}）：`client_credential`，
 *       属主为 `(owner_type, owner_id)` 二元组 —— 入站鉴权**不登记调用方也能调**（发一把密钥即可），
 *       同时因「一凭证一行 + label」保留「单独吊销」与「按标签归因」（设计方案 v1.2 D-CA-18）。</li>
 * </ul>
 *
 * <p>**语义入口仍分开**：{@code CredentialService}（应用 = 供应商：出站签名 / 回调验签）与
 * {@code InboundCredentialService} / {@code ClientCredentialService}（入站鉴权）各自做「属主存在性」检查，
 * 再委托 {@code CredentialStore} 执行机制 —— 这样 `appId` 语义**不会**漂移（D-CA-1 / §7.1 的顾虑）。
 *
 * <p>⚠️ 表名 / 列名 / `owner_type` 取值均来自本枚举的**常量**（绝非用户输入）⇒ 拼进 SQL 安全；
 * {@link #ownerPredicate()} 保证**恰好一个 `?` 占位**（池形态的 `PLATFORM` 用 MySQL 的 NULL 安全比较 `<=>`，
 * 以便调用方统一传 `ownerId`（可为 null），无需按属主分支拼参数）。
 */
public enum CredentialOwner {

    /** 应用（供应商）：`OUTBOUND` 出站签名 / `CALLBACK` 回调验签 */
    APP("app_credential", "app_id", null, List.of("OUTBOUND", "CALLBACK"),
            "应用", "该类型已无有效凭证，出站签名/回调验签将不可用，请立即补发"),

    /** 调用方档案（平台客户，L2 可选精确管控）：入站鉴权方式对应的凭证类型 */
    CLIENT("client_credential", "owner_id", "CLIENT",
            List.of("API_KEY", "HMAC_SECRET", "BEARER_TOKEN", "BASIC"),
            "调用方", "该类型已无有效凭证，调用方入站鉴权将不可用，请立即补发"),

    /** 接口专属池（L1 默认路径之一）：只对某个接口生效的凭证（owner_id = interface.id） */
    INTERFACE("client_credential", "owner_id", "INTERFACE",
            List.of("API_KEY", "HMAC_SECRET", "BEARER_TOKEN", "BASIC"),
            "接口", "该类型已无有效凭证，本接口的入站鉴权将不可用，请立即补发"),

    /** 平台共享池（L1 默认路径，不登记调用方即可接入）：所有未绑定接口通用的凭证（owner_id = NULL） */
    PLATFORM("client_credential", "owner_id", "PLATFORM",
            List.of("API_KEY", "HMAC_SECRET", "BEARER_TOKEN", "BASIC"),
            "平台", "该类型已无有效凭证，所有未绑定鉴权方式的接口将不可用，请立即补发");

    private final String table;
    private final String column;
    private final String ownerType;
    private final List<String> kinds;
    private final String displayName;
    private final String retireWarning;

    CredentialOwner(String table, String column, String ownerType, List<String> kinds,
                    String displayName, String retireWarning) {
        this.table = table;
        this.column = column;
        this.ownerType = ownerType;
        this.kinds = kinds;
        this.displayName = displayName;
        this.retireWarning = retireWarning;
    }

    /** 错误文案用的主体名（应用 / 调用方 / 接口 / 平台） */
    public String displayName() {
        return displayName;
    }

    /** 凭证耗尽时的告警文案（方向措辞不同：应用=出站签名/回调验签；入站=入站鉴权） */
    public String retireWarning() {
        return retireWarning;
    }

    public String table() {
        return table;
    }

    /** 属主取值列名（`app_id` / `owner_id`）—— 行映射与 INSERT 用 */
    public String column() {
        return column;
    }

    /** 是否是「凭证池」形态（`client_credential` 的 `(owner_type, owner_id)`）；`APP` 返回 false */
    public boolean pooled() {
        return ownerType != null;
    }

    /** `owner_type` 取值（`APP` 为 null） */
    public String ownerType() {
        return ownerType;
    }

    /** 平台共享池的 `owner_id` 为 NULL（`<=>` 比较，调用方统一传 null） */
    public boolean nullOwnerId() {
        return this == PLATFORM;
    }

    /**
     * SQL WHERE 用的**属主谓词**（常量字符串，**恰好一个 `?` 占位**）：
     * <ul>
     *   <li>应用形态：`app_id = ?`</li>
     *   <li>池形态：`owner_type = 'CLIENT|INTERFACE|PLATFORM' AND owner_id = ?`（`PLATFORM` 用 `<=>` 以匹配 NULL）</li>
     * </ul>
     * 谓词里的取值是本枚举常量（非用户输入）⇒ 无注入面；单占位符让仓储层所有方法**参数形状统一**。
     */
    public String ownerPredicate() {
        if (!pooled()) {
            return column + " = ?";
        }
        return "owner_type = '" + ownerType + "' AND owner_id " + (nullOwnerId() ? "<=> ?" : "= ?");
    }

    /** 追加 `owner_type`（仅池形态）——供 ORDER BY / GROUP BY 分组用 */
    public String ownerLabel() {
        return pooled() ? ownerType : "";
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

    /**
     * 行映射器：应用形态读 `app_id` 且无 `label` 列；池形态读 `owner_id` + `label`（v1.2）。
     * 放在这里是为了让仓储层每个方法都用同一个映射，**避免「有的方法读了 label、有的没读」的静默不一致**。
     */
    public RowMapper<CredentialRow> rowMapper() {
        return pooled()
                ? (rs, i) -> CredentialRow.ofPooled(rs)
                : (rs, i) -> CredentialRow.ofApp(rs);
    }
}
