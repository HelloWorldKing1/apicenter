package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.OutboundRequestRow;
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
 * outbound_request 运行表数据访问（Flow A 状态机载体）+ 死信写入（dead_letter 表）。
 * 补偿 worker 按 (status, next_retry_at) 扫描（设计 §6.3 / 技术架构 §2.3 表驱动状态机）。
 */
@Repository
public class OutboundRequestRepository {

    private final JdbcTemplate jdbc;

    public OutboundRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建出站请求记录（status=INIT）并返回自增主键 */
    public long insert(OutboundRequestRow row) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO outbound_request (interface_id, app_id, biz_id, in_payload, out_payload,
                                                  resp_payload, status, attempt_count, max_attempts,
                                                  next_retry_at, error_code, trace_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, row.interfaceId());
            ps.setString(2, row.appId());
            ps.setString(3, row.bizId());
            ps.setString(4, row.inPayload());
            ps.setString(5, row.outPayload());
            ps.setString(6, row.respPayload());
            ps.setString(7, row.status());
            ps.setInt(8, row.attemptCount());
            ps.setInt(9, row.maxAttempts());
            ps.setTimestamp(10, row.nextRetryAt() == null ? null
                    : java.sql.Timestamp.valueOf(row.nextRetryAt()));
            ps.setString(11, row.errorCode());
            ps.setString(12, row.traceId());
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1 : key.longValue();
    }

    public Optional<OutboundRequestRow> findById(long id) {
        return jdbc.query("SELECT * FROM outbound_request WHERE id = ?", OutboundRequestRow.MAPPER, id)
                .stream().findFirst();
    }

    /** 按业务键查（测试断言 / 对账定位用） */
    public List<OutboundRequestRow> findByBizId(String appId, String bizId) {
        return jdbc.query("SELECT * FROM outbound_request WHERE app_id = ? AND biz_id = ? ORDER BY id DESC",
                OutboundRequestRow.MAPPER, appId, bizId);
    }

    /** 测试 / 运维清理：按应用删除运行数据（含其死信） */
    public int deleteByApp(String appId) {
        jdbc.update("DELETE FROM dead_letter WHERE ref_id IN (SELECT id FROM outbound_request WHERE app_id = ?)", appId);
        return jdbc.update("DELETE FROM outbound_request WHERE app_id = ?", appId);
    }

    /** 接口的运行数据条数（删除守卫：存在运行数据仅允许下线，schema.sql 删除策略） */
    public int countByInterface(long interfaceId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbound_request WHERE interface_id = ?", Integer.class, interfaceId);
        return n == null ? 0 : n;
    }

    /** 状态流转（含诊断字段与下次重试时间；传 null 表示不改）。attempt_count 由 incrementAttempt 显式维护 */
    public int updateState(long id, String status, String outPayload, String respPayload,
                           LocalDateTime nextRetryAt, String errorCode) {
        return jdbc.update("""
                UPDATE outbound_request
                SET status = ?, out_payload = COALESCE(?, out_payload), resp_payload = COALESCE(?, resp_payload),
                    next_retry_at = ?, error_code = COALESCE(?, error_code)
                WHERE id = ?
                """, status, outPayload, respPayload,
                nextRetryAt == null ? null : java.sql.Timestamp.valueOf(nextRetryAt),
                errorCode, id);
    }

    /** 尝试次数 +1（补偿重放前调用；首送时 attempt_count=1） */
    public int incrementAttempt(long id) {
        return jdbc.update("UPDATE outbound_request SET attempt_count = attempt_count + 1 WHERE id = ?", id);
    }

    /** 补偿 worker 扫描：到期可重试的 COMPENSATING 记录（按 (status, next_retry_at) 索引） */
    public List<OutboundRequestRow> findDueCompensating(LocalDateTime now) {
        return jdbc.query("""
                SELECT * FROM outbound_request
                WHERE status = 'COMPENSATING' AND (next_retry_at IS NULL OR next_retry_at <= ?)
                ORDER BY next_retry_at LIMIT 100
                """, OutboundRequestRow.MAPPER, java.sql.Timestamp.valueOf(now));
    }

    /** 死信落库（设计 §6.1：4xx / 重试耗尽 / 补偿耗尽） */
    public void insertDeadLetter(String bizType, long refId, String reason, String payload) {
        jdbc.update("""
                INSERT INTO dead_letter (biz_type, ref_id, reason, payload, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, bizType, refId, reason, payload);
    }

    /** 死信计数（并发双扫防重复插入：补偿 worker 可被调度与测试手动并发调用，同一记录至多一条死信） */
    public int countDeadLetter(String bizType, long refId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dead_letter WHERE biz_type = ? AND ref_id = ?", Integer.class, bizType, refId);
        return n == null ? 0 : n;
    }
}
