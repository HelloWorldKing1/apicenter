package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.InboundDeliveryRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * inbound_delivery 运行表数据访问（Flow B 送达状态机载体，M3 交付）。
 * 补偿 worker 按 (delivery_status, next_retry_at) 索引扫描 PENDING 重送（设计 §6.3 / schema idx_delivery_scan）。
 */
@Repository
public class InboundDeliveryRepository {

    private final JdbcTemplate jdbc;

    public InboundDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建送达记录（首期落库即 PENDING，D-M3-2）并返回自增主键 */
    public long insert(InboundDeliveryRow row) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO inbound_delivery (interface_id, app_id, callback_event_id, payload,
                                                  callback_url_snapshot, delivery_status, attempt_count,
                                                  max_attempts, next_retry_at, ack_to_partner, trace_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, row.interfaceId());
            ps.setString(2, row.appId());
            ps.setString(3, row.callbackEventId());
            ps.setString(4, row.payload());
            ps.setString(5, row.callbackUrlSnapshot());
            ps.setString(6, row.deliveryStatus());
            ps.setInt(7, row.attemptCount());
            ps.setInt(8, row.maxAttempts());
            ps.setTimestamp(9, row.nextRetryAt() == null ? null : java.sql.Timestamp.valueOf(row.nextRetryAt()));
            ps.setString(10, row.ackToPartner());
            ps.setString(11, row.traceId());
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1 : key.longValue();
    }

    public Optional<InboundDeliveryRow> findById(long id) {
        return jdbc.query("SELECT * FROM inbound_delivery WHERE id = ?", InboundDeliveryRow.MAPPER, id)
                .stream().findFirst();
    }

    /** 按 traceId 回查（「模拟回调」测试端点展示送达状态用） */
    public List<InboundDeliveryRow> findByTrace(String traceId) {
        return jdbc.query("SELECT * FROM inbound_delivery WHERE trace_id = ? ORDER BY id DESC",
                InboundDeliveryRow.MAPPER, traceId);
    }

    /** 状态流转（含下次重送时间；传 null 表示不改）。attempt_count 由 incrementAttempt 显式维护 */
    public int updateState(long id, String status, LocalDateTime nextRetryAt) {
        return jdbc.update("""
                UPDATE inbound_delivery
                SET delivery_status = ?, next_retry_at = ?
                WHERE id = ?
                """, status, nextRetryAt == null ? null : java.sql.Timestamp.valueOf(nextRetryAt), id);
    }

    /** 条件认领重放（评审遗漏 6 修复）：仅 PENDING 才 attempt+1——并发双扫（调度 + 手动 scan）时
     *  已被他线程处理至 ACKED/DEAD 的记录不再重复送达。返回 false = 未认领（调用方放弃重放）。 */
    public boolean claimForRedeliver(long id) {
        return jdbc.update("""
                UPDATE inbound_delivery SET attempt_count = attempt_count + 1
                WHERE id = ? AND delivery_status = 'PENDING'
                """, id) > 0;
    }

    /** 补偿 worker 扫描：到期可重送的 PENDING 记录（按 (delivery_status, next_retry_at) 索引） */
    public List<InboundDeliveryRow> findDuePending(LocalDateTime now) {
        return jdbc.query("""
                SELECT * FROM inbound_delivery
                WHERE delivery_status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= ?)
                ORDER BY next_retry_at LIMIT 100
                """, InboundDeliveryRow.MAPPER, java.sql.Timestamp.valueOf(now));
    }

    /** 接口的运行数据条数（删除守卫：存在运行数据仅允许下线，M3 补查 inbound_delivery） */
    public int countByInterface(long interfaceId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbound_delivery WHERE interface_id = ?", Integer.class, interfaceId);
        return n == null ? 0 : n;
    }

    /** 测试 / 运维清理：按应用删除运行数据（含其死信） */
    public int deleteByApp(String appId) {
        jdbc.update("DELETE FROM dead_letter WHERE ref_id IN (SELECT id FROM inbound_delivery WHERE app_id = ?)", appId);
        return jdbc.update("DELETE FROM inbound_delivery WHERE app_id = ?", appId);
    }
}
