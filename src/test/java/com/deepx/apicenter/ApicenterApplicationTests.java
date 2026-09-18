package com.deepx.apicenter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 冒烟测试：验证 Spring 上下文可加载（需 MySQL/PolarDB 可达、8080 端口空闲）。
 * 注意：上下文会被测试框架缓存并在整个 suite 存活——必须禁用后台 worker，
 * 否则其默认 3s/30s 调度线程会在后续各集成测试执行期并发扫描共享 dev 库运行表
 * （曾致 M4 熔断/对账用例偶发：短路采样窗口内被重放、UNKNOWN 被抢置）。
 */
@SpringBootTest(properties = {
        "app.api-center.retry-worker-fixed-delay-ms=3600000",
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        // 首跑延迟置大（2026-09-18 隔离修复）：initial-delay 默认 0 = 上下文启动即跑一轮全局扫描，
        // 会与其他测试类的用例、以及库中历史残留行竞态（scan() 不按应用过滤）
        "app.api-center.retry-worker-initial-delay-ms=3600000",
        "app.api-center.alert-worker-initial-delay-ms=3600000"
})
class ApicenterApplicationTests {

    @Test
    void contextLoads() {
    }
}
