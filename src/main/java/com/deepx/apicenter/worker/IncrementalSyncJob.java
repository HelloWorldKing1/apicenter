package com.deepx.apicenter.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.sql.Timestamp;

/**
 * 定时增量拉取（设计文档 §8，能力 3.4）。
 *
 * <p>流程：读高水位游标 → 拉取第三方增量数据 → 幂等防重 → 推进游标 + 写 sync_job_log。
 * Demo 用 {@code @Scheduled} 每 60s 触发；生产接入 XXL-JOB 执行器
 * （{@code @XxlJob("orderIncrementalPull")} 即为同一方法体入口）。
 *
 * <p>防重：{@code idempotency_key} 唯一键 {@code (biz_type, channel_code, biz_id)}，
 * 重复写入跳过并计 dedup_count；生产用分布式锁（Redis SETNX）包住整个拉取窗口（设计文档 §8.2）。
 */
@Component
public class IncrementalSyncJob {

    private static final Logger log = LoggerFactory.getLogger(IncrementalSyncJob.class);

    private final JdbcTemplate jdbcTemplate;

    public IncrementalSyncJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 每 60 秒触发（Demo 周期；生产由 XXL-JOB 调度）。
     */
    @Scheduled(fixedDelayString = "60000")
    public void pullIncremental() {
        // 1) 读游标（无记录返回 null）
        String cursor = jdbcTemplate.query(
                "SELECT watermark_value FROM sync_watermark WHERE sync_job = ? AND channel_code = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                "ORDER_INCREMENTAL", "PARTNER_A");
        log.info("[sync-job] 当前游标={}，触发增量拉取", cursor);

        // 2) 拉取第三方增量（示例占位：调 PartnerAClient 按 createdAt > cursor 分页拉取）
        //    List<OrderDto> pulled = partnerAClient.listNewerThan(cursor);

        // 3) 幂等防重：对每个 bizId 尝试 INSERT INTO idempotency_key(...)；
        //    唯一键冲突 → 跳过并 dedupCount++（示例省略具体行）

        // 4) 推进游标 + 写 sync_job_log
        jdbcTemplate.update("""
                MERGE INTO sync_watermark (sync_job, channel_code, watermark_value, updated_at)
                KEY(sync_job, channel_code) VALUES (?, ?, ?, ?)
                """,
                "ORDER_INCREMENTAL", "PARTNER_A", cursor == null ? "0" : cursor, Timestamp.valueOf(LocalDateTime.now()));
        jdbcTemplate.update("""
                INSERT INTO sync_job_log (job_name, channel_code, start_watermark, end_watermark,
                                          pulled_count, dedup_count, status, run_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "ORDER_INCREMENTAL", "PARTNER_A", cursor, cursor, 0, 0, "SUCCESS",
                Timestamp.valueOf(LocalDateTime.now()));
    }
}
