package com.deepx.apicenter;

import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.service.InboundAuthSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入站鉴权 **v1.2 平台设置 C1** 集成测试（2026-09-24，《入站鉴权设计方案》v1.2 §19.2 用例 51–53）。
 *
 * <p>覆盖：**设置表恒一行** / **改完即时生效（无需重启）** / **引用校验（fail-closed 前置条件）** /
 * **放松类变更留痕（alert_event）** / **影响面预览**。
 *
 * <p>为什么这些必须测：平台默认方式**影响所有未绑定 `CLIENT_AUTH` 的接口** ——
 * 一个「页面显示已改、实际没生效」或「保存成功但指向停用适配器」都会让线上要么静默放宽、要么整片 40108。
 *
 * <p>注意：四个 worker 隔离属性照 CLAUDE.md 硬要求全置大；用例结束把设置恢复默认（避免影响其他用例/手动验收）。
 */
@SpringBootTest(properties = {
        "app.api-center.retry-worker-fixed-delay-ms=3600000",
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-initial-delay-ms=3600000",
        "app.api-center.alert-worker-initial-delay-ms=3600000"
})
class InboundAuthSettingIntegrationTest {

    private static final String TEMP_DISABLED_ADAPTER = "ADP-TST-DISABLED";

    @Autowired
    private InboundAuthSettingService settingService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 恢复默认设置（平台共享池的默认态：方式未配置 + 强制自报主体），并清理临时适配器 */
    @AfterEach
    void restore() {
        settingService.save(null, true, "test-cleanup");
        jdbcTemplate.update("DELETE FROM adapter WHERE id = ?", TEMP_DISABLED_ADAPTER);
    }

    @Test
    void 设置表恒一行_重复保存不产生第二行() {
        for (int i = 0; i < 3; i++) {
            settingService.save(null, true, "test");
        }
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inbound_auth_setting", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void 改设置即时生效_缓存不返回旧值() {
        String authAdapterId = enabledAuthAdapterId();
        settingService.save(authAdapterId, true, "test");

        // 立即读（同一进程、未经重启）：证明「保存 → 失效缓存」链路有效（TTL 兜底是 60s，不参与本断言）
        assertThat(settingService.defaultAdapterId()).isEqualTo(authAdapterId);
        assertThat(settingService.requireClientId()).isTrue();

        settingService.save(authAdapterId, false, "test");
        assertThat(settingService.requireClientId()).isFalse();
        assertThat(settingService.view().updatedBy()).isEqualTo("test");
    }

    @Test
    void 平台默认必须是存在_启用_且类型为auth的适配器() {
        assertThatThrownBy(() -> settingService.save("NO-SUCH-ADAPTER", true, "test"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("鉴权适配器不存在");

        String nonAuthAdapterId = jdbcTemplate.queryForObject(
                "SELECT id FROM adapter WHERE type <> 'auth' LIMIT 1", String.class);
        assertThatThrownBy(() -> settingService.save(nonAuthAdapterId, true, "test"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须是鉴权类适配器");

        jdbcTemplate.update("INSERT INTO adapter (id, name, type, impl, enabled, version, params) "
                + "VALUES (?, '测试停用适配器', 'auth', 'ClientApiKeyVerifyAdapter', 0, 'v1', '{}')",
                TEMP_DISABLED_ADAPTER);
        assertThatThrownBy(() -> settingService.save(TEMP_DISABLED_ADAPTER, true, "test"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已停用");
    }

    @Test
    void 放松类变更落告警事件_供追溯谁放宽了鉴权() {
        String authAdapterId = enabledAuthAdapterId();
        // 方式变更（宽严无法自动判定 ⇒ 一律按放松留痕）
        settingService.save(authAdapterId, true, "tester");
        Integer byAdapter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert_event WHERE metric = 'inbound_auth_setting_changed'", Integer.class);
        assertThat(byAdapter).isGreaterThanOrEqualTo(1);
        String message = jdbcTemplate.queryForObject(
                "SELECT message FROM alert_event WHERE metric = 'inbound_auth_setting_changed' "
                        + "ORDER BY id DESC LIMIT 1", String.class);
        assertThat(message).contains("tester");

        // 强制自报主体 1 → 0（明确放松）
        jdbcTemplate.update("DELETE FROM alert_event WHERE metric = 'inbound_auth_setting_changed'");
        settingService.save(authAdapterId, false, "tester");
        Integer byRequire = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert_event WHERE metric = 'inbound_auth_setting_changed'", Integer.class);
        assertThat(byRequire).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 影响面预览_返回未绑定鉴权方式的出站中转接口数() {
        int affected = settingService.affectedInterfaceCount();
        // CLIENT_AUTH 角色由 C3 落地；在此之前，所有未下线的出站中转接口都受平台默认影响
        assertThat(affected).isGreaterThanOrEqualTo(0);
        Integer outbound = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interface WHERE if_type = 'OUTBOUND' AND status <> 'OFFLINE'", Integer.class);
        assertThat(affected).isLessThanOrEqualTo(outbound);
    }

    /** 语义化取一个「启用的鉴权适配器」id（不写死 seed 资产，避免库态变化变红） */
    private String enabledAuthAdapterId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM adapter WHERE type = 'auth' AND enabled = 1 ORDER BY id LIMIT 1", String.class);
    }
}
