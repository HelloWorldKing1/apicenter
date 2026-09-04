package com.deepx.apicenter.service;

import com.deepx.apicenter.model.AlertRuleRow;
import com.deepx.apicenter.repository.AlertEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * AlertService 单测（M4 计划 §4）：四指标表达式命中与不命中 / 非法表达式跳过不崩 /
 * 冷却期去重 / 验签连续失败内置告警（阈值触发 + 窗口重置）。
 */
class AlertServiceTest {

    private final AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
    private final AlertService service = new AlertService(alertEventRepository);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "cooldownMinutes", 5L);
        ReflectionTestUtils.setField(service, "verifyFailThreshold", 3);
        service.reset();
    }

    private AlertRuleRow rule(long id, String metric, String threshold) {
        return new AlertRuleRow(id, "规则" + id, metric, threshold, null, true, null, null);
    }

    @Test
    void 表达式命中_落库告警事件() {
        boolean fired = service.evaluateAndFire(rule(1, "dead_letter_backlog", "> 100"), 150.0);
        assertThat(fired).isTrue();
        // 首参为 rule_id：规则告警传规则 id（内置告警才传 null）
        verify(alertEventRepository).insert(org.mockito.ArgumentMatchers.eq(1L), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 表达式不命中_不落库() {
        assertThat(service.evaluateAndFire(rule(1, "success_rate", "< 95"), 99.0)).isFalse();
        verify(alertEventRepository, never()).insert(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 四种运算符语义正确() {
        assertThat(service.evaluateAndFire(rule(1, "m", "> 100"), 100.0)).isFalse();   // 等于不算大于
        assertThat(service.evaluateAndFire(rule(2, "m", ">= 100"), 100.0)).isTrue();
        assertThat(service.evaluateAndFire(rule(3, "m", "< 95"), 95.0)).isFalse();     // 等于不算小于
        assertThat(service.evaluateAndFire(rule(4, "m", "<= 95"), 95.0)).isTrue();
    }

    @Test
    void 非法表达式_跳过不崩() {
        assertThat(service.evaluateAndFire(rule(1, "m", "无运算符"), 100.0)).isFalse();
        assertThat(service.evaluateAndFire(rule(2, "m", "> abc"), 100.0)).isFalse();
        assertThat(service.evaluateAndFire(rule(3, "m", null), 100.0)).isFalse();
        verify(alertEventRepository, never()).insert(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 冷却期内_同规则不重复落库() {
        assertThat(service.evaluateAndFire(rule(1, "dead_letter_backlog", "> 10"), 20.0)).isTrue();
        assertThat(service.evaluateAndFire(rule(1, "dead_letter_backlog", "> 10"), 30.0)).isFalse(); // 冷却内
        verify(alertEventRepository, times(1)).insert(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 不同规则互不冷却() {
        assertThat(service.evaluateAndFire(rule(1, "dead_letter_backlog", "> 10"), 20.0)).isTrue();
        assertThat(service.evaluateAndFire(rule(2, "retry_backlog", "> 10"), 20.0)).isTrue();
        verify(alertEventRepository, times(2)).insert(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 失败后冷却_规则再次命中不落库但状态保持() {
        // 触发一次 → 冷却；指标回落 → 再超阈值：仍冷却中不落库
        service.evaluateAndFire(rule(1, "m", "> 10"), 20.0);
        assertThat(service.evaluateAndFire(rule(1, "m", "> 10"), 50.0)).isFalse();
        assertThat(service.evaluateAndFire(rule(1, "m", "> 10"), 50.0)).isFalse();
        verify(alertEventRepository, times(1)).insert(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 验签连续失败_达阈值触发内置告警并重置窗口() {
        for (int i = 0; i < 2; i++) {
            service.recordVerifyFailure("APP-1"); // 阈值 3：前 2 次不触发
        }
        verify(alertEventRepository, never()).insert(any(), anyString(), anyString(), anyString(), anyString());
        service.recordVerifyFailure("APP-1"); // 第 3 次触发
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(alertEventRepository).insert(isNull(), org.mockito.ArgumentMatchers.eq("verify_fail_streak"),
                org.mockito.ArgumentMatchers.eq("CRITICAL"), messageCaptor.capture(), anyString());
        assertThat(messageCaptor.getValue()).contains("APP-1").contains("3 次");
        // 窗口已重置：再 2 次失败不重复触发
        service.recordVerifyFailure("APP-1");
        service.recordVerifyFailure("APP-1");
        verify(alertEventRepository, times(1)).insert(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 验签失败_不同应用独立计数() {
        for (int i = 0; i < 3; i++) {
            service.recordVerifyFailure("APP-1");
        }
        service.recordVerifyFailure("APP-2"); // APP-2 仅 1 次
        verify(alertEventRepository, times(1)).insert(any(), anyString(), anyString(), anyString(), anyString());
    }
}
