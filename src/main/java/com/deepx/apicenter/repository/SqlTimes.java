package com.deepx.apicenter.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 时间列写入的统一口径（2026-09-12 修复 C3 抖动）：
 * `next_retry_at` 等列是 MySQL `DATETIME`（0 位小数），驱动写入带毫秒的值时 **MySQL 会四舍五入到秒**——
 * 例如 `11:09:14.845` 存成 `11:09:15`，于是「立即入队（next_retry_at=now）」的补偿记录在同一秒内被
 * `findDueCompensating(now)` 判定为**未到期**（worker 要等下一秒才捞到）。
 *
 * <p>统一在写入前把纳秒截断到秒（`withNano(0)`），保证：
 * <ul>
 *   <li>写入值不再被四舍五入到下一秒 → 「立即入队」语义真实成立；</li>
 *   <li>调度时间最多提前 &lt;1 秒（重试间隔均为秒级，无实质影响）。</li>
 * </ul>
 */
final class SqlTimes {

    private SqlTimes() {
    }

    /** LocalDateTime → Timestamp（截断到秒；null 透传） */
    static Timestamp ts(LocalDateTime time) {
        return time == null ? null : Timestamp.valueOf(time.withNano(0));
    }
}
