package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.InboundAuthSettingRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 入站鉴权平台设置仓储（**单行表** `inbound_auth_setting`，2026-09-24 v1.2）。
 *
 * <p><b>单行不变量</b>：`id` 恒为 1 —— {@link #save} 只做 `UPDATE ... WHERE id = 1`（**不 INSERT**），
 * 初始化由 {@link #ensureRow()}（`INSERT IGNORE`）兜底；这样多人同时打开设置页也插不出第二行
 * （回归：`InboundAuthSettingIntegrationTest#设置表恒一行_重复保存不产生第二行`）。
 *
 * <p><b>影响面预览</b>：{@link #countInterfacesWithoutClientAuthBinding()} 回答「有多少接口会受
 * 平台默认变更影响」——即**未绑定 `CLIENT_AUTH` 角色**的出站中转接口数（接口级绑定优先于平台默认，
 * 设计方案 v1.2 §12.3）。
 */
@Repository
public class InboundAuthSettingRepository {

    private static final String SELECT_ONE = "SELECT default_adapter_id, require_client_id, updated_by, updated_at "
            + "FROM inbound_auth_setting WHERE id = 1";

    private final JdbcTemplate jdbc;

    public InboundAuthSettingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读设置（行不存在时返回空 —— 调用方回退到「未配置」语义：方式未配 ⇒ fail-closed 40108） */
    public Optional<InboundAuthSettingRow> find() {
        return jdbc.query(SELECT_ONE, (rs, i) -> new InboundAuthSettingRow(
                rs.getString("default_adapter_id"),
                rs.getBoolean("require_client_id"),
                rs.getString("updated_by"),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime()
        )).stream().findFirst();
    }

    /** 保存（**只 UPDATE 单行**；不产生第二行） */
    public int save(String defaultAdapterId, boolean requireClientId, String updatedBy) {
        return jdbc.update("UPDATE inbound_auth_setting SET default_adapter_id = ?, require_client_id = ?, "
                + "updated_by = ? WHERE id = 1", defaultAdapterId, requireClientId, updatedBy);
    }

    /** 初始化兜底（幂等）：数据库未预置单行时补一行默认值 */
    public int ensureRow() {
        return jdbc.update("INSERT IGNORE INTO inbound_auth_setting (id, default_adapter_id, require_client_id) "
                + "VALUES (1, NULL, 1)");
    }

    /**
     * 受平台默认影响的接口数 = **未绑定 `CLIENT_AUTH` 且未下线的出站中转接口**数
     * （入站回调接口走链内回调验签，与调用方鉴权无关）。
     */
    public int countInterfacesWithoutClientAuthBinding() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interface i WHERE i.if_type = 'OUTBOUND' AND i.status <> 'OFFLINE' "
                        + "AND NOT EXISTS (SELECT 1 FROM interface_adapter_binding b "
                        + "                WHERE b.interface_id = i.id AND b.role = 'CLIENT_AUTH')",
                Integer.class);
        return n == null ? 0 : n;
    }
}
