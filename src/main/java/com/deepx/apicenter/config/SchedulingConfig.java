package com.deepx.apicenter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务线程池（2026-09-12 修复）：
 * 默认 `@Scheduled` 使用**单线程**调度器——补偿扫描（fixedDelay 3s，可能重放上百条）与告警评估（30s）
 * 会互相排队，导致告警评估被推迟、补偿周期被拉长。这里显式给 2 个线程（线程名带前缀便于排障），
 * 保持「同一任务不并发重入」的默认语义（fixedDelay）。
 *
 * <p>注：{@code ApicenterApplication} 已有 {@code @EnableScheduling}，此处只提供 TaskScheduler Bean。
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("apicenter-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
