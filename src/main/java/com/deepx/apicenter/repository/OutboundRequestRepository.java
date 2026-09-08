package com.deepx.apicenter.repository;

import com.deepx.apicenter.model.OutboundRequestRow;
import com.deepx.apicenter.model.OutboundRequestStateLogRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * outbound_request 运行表数据访问（Flow A 状态机载体）+ 死信写入（dead_letter 表）+
 * 状态链写入（outbound_request_state_log，M5 后设计 §4.6：事件溯源 append-only）。
 * 补偿 worker 按 (status, next_retry_at) 扫描（设计 §6.3 / 技术架构 §2.3 表驱动状态机）。
 *
 * <p>状态链埋点收敛在本类：状态「真正变化」（from ≠ to）才追加 state_log 一行；
 * from==to 的顺延（如熔断期 COMPENSATING→COMPENSATING 只改 next_retry_at）只走 updateState，不产生节点。
 */
@Repository
public class OutboundRequestRepository {

    /** 状态链 trigger 常量（逻辑名；物理列 trigger_src——trigger 为 MySQL 保留字） */
    public static final String TRIGGER_FIRST_SEND = "FIRST_SEND";
    public static final String TRIGGER_COMPENSATE = "COMPENSATE";
    public static final String TRIGGER_CIRCUIT_OPEN = "CIRCUIT_OPEN";
    public static final String TRIGGER_RECONCILE_MANUAL = "RECONCILE_MANUAL";
    public static final String TRIGGER_TTL_DOWNGRADE = "TTL_DOWNGRADE";
    public static final String TRIGGER_REPLAY = "REPLAY";
    public static final String TRIGGER_EXHAUSTED = "EXHAUSTED";

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

    /**
     * 状态流转（含诊断字段与下次重试时间；传 null 表示不改）。attempt_count 由 incrementAttempt 显式维护。
     * <p>M5 后状态链：状态「真正变化」（from ≠ to）时追加 state_log 一行（from/attempt/trace 在更新前读取，
     * 同一次转移内一致）；from==to 的同状态顺延（如熔断期 COMPENSATING→COMPENSATING 只改 next_retry_at）
     * 不产生节点。调用点须传 trigger / detail（trigger 见类常量）。
     */
    public int transition(long id, String toStatus, String outPayload, String respPayload,
                          LocalDateTime nextRetryAt, String errorCode,
                          String trigger, String detail) {
        // 1. 读更新前状态 / attempt / trace_id / 下一 seq（单条记录由状态机串行驱动：首送请求线程、
        //    worker 单线程扫描、对账操作，同一时刻至多一个执行者，无并发覆盖风险）
        Map<String, Object> cur = jdbc.queryForMap("""
                SELECT status, attempt_count, trace_id, error_code,
                       (SELECT COALESCE(MAX(seq), 0) + 1 FROM outbound_request_state_log
                         WHERE outbound_request_id = ?) AS next_seq
                FROM outbound_request WHERE id = ?
                """, id, id);
        String from = (String) cur.get("status");
        int attempt = ((Number) cur.get("attempt_count")).intValue();
        String traceId = (String) cur.get("trace_id");
        String prevErrorCode = (String) cur.get("error_code");
        int nextSeq = ((Number) cur.get("next_seq")).intValue();
        // 2. 状态更新（原 updateState 语义）
        int updated = updateState(id, toStatus, outPayload, respPayload, nextRetryAt, errorCode);
        // 3. 真正变化才记状态链（error_code 记变更后的值；变更后为 null 时保留旧值语义由 detail 承载）
        if (updated > 0 && from != null && !from.equals(toStatus)) {
            insertLog(id, nextSeq, from, toStatus, attempt,
                    errorCode == null ? prevErrorCode : errorCode, trigger, detail, traceId);
        }
        return updated;
    }

    /** 状态流转（不记状态链）：同状态顺延 / 仅刷新诊断字段时使用（如熔断期 COMPENSATING→COMPENSATING 顺延） */
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

    /** 状态链追加（私有：transition / degrade / reset 共用） */
    private void insertLog(long requestId, int seq, String from, String to, int attempt,
                           String errorCode, String trigger, String detail, String traceId) {
        jdbc.update("""
                INSERT INTO outbound_request_state_log
                    (outbound_request_id, seq, from_status, to_status, attempt, error_code, trigger_src, detail, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, requestId, seq, from, to, attempt, errorCode, trigger, detail, traceId);
    }

    /** 某条出站记录的状态链（监控详情展示；按 seq 升序 = 时间序） */
    public List<OutboundRequestStateLogRow> stateChain(long outboundRequestId) {
        return jdbc.query("""
                SELECT * FROM outbound_request_state_log
                WHERE outbound_request_id = ? ORDER BY seq, id
                """, OutboundRequestStateLogRow.MAPPER, outboundRequestId);
    }

    // ---------- 状态链批量（主请求路径用：状态列即时 updateState，节点攒批后一次落库） ----------

    /** 待落库节点（from/to/attempt 由调用点按状态机确定性提供；attempt = 该轮请求的尝试计数） */
    public record StateChainNode(String fromStatus, String toStatus, int attempt,
                                 String errorCode, String trigger, String detail) {
    }

    /**
     * 批量落状态链（主请求路径 execute/replay 出口调用）：一次 SQL 写入本请求攒批的全部节点，
     * seq 从该请求当前 max(seq) 续起。设计取舍：远程库下逐节点 SELECT+INSERT 会显著拖慢主链路
     * （每节点多 1 次往返，熔断窗口/压测时序敏感），批量后主请求路径每请求仅增 1-2 次往返。
     */
    public int flushStateChain(long requestId, String traceId, List<StateChainNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return 0;
        }
        Integer maxSeq = jdbc.queryForObject("""
                SELECT COALESCE(MAX(seq), 0) FROM outbound_request_state_log
                WHERE outbound_request_id = ?
                """, Integer.class, requestId);
        int seq = maxSeq == null ? 0 : maxSeq;
        StringBuilder sql = new StringBuilder("""
                INSERT INTO outbound_request_state_log
                    (outbound_request_id, seq, from_status, to_status, attempt, error_code, trigger_src, detail, trace_id)
                VALUES
                """);
        List<Object> args = new java.util.ArrayList<>();
        for (StateChainNode n : nodes) {
            sql.append("(?, ?, ?, ?, ?, ?, ?, ?, ?),");
            args.add(requestId);
            args.add(++seq);
            args.add(n.fromStatus());
            args.add(n.toStatus());
            args.add(n.attempt());
            args.add(n.errorCode());
            args.add(n.trigger());
            args.add(n.detail());
            args.add(traceId);
        }
        sql.setLength(sql.length() - 1); // 去尾逗号
        return jdbc.update(sql.toString(), args.toArray());
    }


    /** 尝试次数 +1（补偿重放前调用；首送时 attempt_count=1） */
    public int incrementAttempt(long id) {
        return jdbc.update("UPDATE outbound_request SET attempt_count = attempt_count + 1 WHERE id = ?", id);
    }

    /**
     * UNKNOWN 对账 / TTL 降级 → COMPENSATING：attempt 清零 + 指定 next_retry_at。
     * 首送预算已随 UNKNOWN 挂起消耗（attempt ≥ max_attempts 会被 worker 直接判死信），
     * 对账重放需新预算（与死信重放 resetForReplay 同一口径）；带 status='UNKNOWN' 条件防并发重复降级。
     * M5 后状态链：降级成功（UPDATE>0）即追加 UNKNOWN→COMPENSATING（attempt 记 0 = 新预算起点）。
     */
    public int degradeUnknownToCompensating(long id, LocalDateTime nextRetryAt,
                                            String trigger, String detail) {
        int updated = jdbc.update("""
                UPDATE outbound_request
                SET status = 'COMPENSATING', attempt_count = 0, next_retry_at = ?
                WHERE id = ? AND status = 'UNKNOWN'
                """, nextRetryAt == null ? null : java.sql.Timestamp.valueOf(nextRetryAt), id);
        if (updated > 0) {
            Map<String, Object> cur = jdbc.queryForMap("""
                    SELECT trace_id, error_code,
                           (SELECT COALESCE(MAX(seq), 0) + 1 FROM outbound_request_state_log
                             WHERE outbound_request_id = ?) AS next_seq
                    FROM outbound_request WHERE id = ?
                    """, id, id);
            insertLog(id, ((Number) cur.get("next_seq")).intValue(), "UNKNOWN", "COMPENSATING", 0,
                    (String) cur.get("error_code"), trigger, detail, (String) cur.get("trace_id"));
        }
        return updated;
    }

    /** 补偿 worker 扫描：到期可重试的 COMPENSATING 记录（按 (status, next_retry_at) 索引） */
    public List<OutboundRequestRow> findDueCompensating(LocalDateTime now) {
        return jdbc.query("""
                SELECT * FROM outbound_request
                WHERE status = 'COMPENSATING' AND (next_retry_at IS NULL OR next_retry_at <= ?)
                ORDER BY next_retry_at LIMIT 100
                """, OutboundRequestRow.MAPPER, java.sql.Timestamp.valueOf(now));
    }

    /** 对账 TTL 扫描（M4 交付，D-M4-2）：UNKNOWN 持续超 unknown_ttl 的记录 → 自动降级 COMPENSATING */
    public List<OutboundRequestRow> findUnknownExpired(LocalDateTime expireBefore) {
        return jdbc.query("""
                SELECT * FROM outbound_request
                WHERE status = 'UNKNOWN' AND updated_at <= ?
                ORDER BY id LIMIT 100
                """, OutboundRequestRow.MAPPER, java.sql.Timestamp.valueOf(expireBefore));
    }

    /** 死信重放状态重置（M4 交付，D-M4-3）：置回 COMPENSATING、attempt 清零（防立即再转死信死循环）、
     *  next_retry_at=now 由 worker 自然扫描重放（重放复用既有 replay 路径，零新执行逻辑）。
     * M5 后状态链：重置成功即追加 DEAD_LETTER→COMPENSATING（attempt 记 0 = 新预算起点；REPLAY）。 */
    public int resetForReplay(long id) {
        String from = jdbc.queryForObject(
                "SELECT status FROM outbound_request WHERE id = ?", String.class, id);
        int updated = jdbc.update("""
                UPDATE outbound_request
                SET status = 'COMPENSATING', attempt_count = 0, next_retry_at = NOW()
                WHERE id = ?
                """, id);
        if (updated > 0 && from != null && !"COMPENSATING".equals(from)) {
            Map<String, Object> cur = jdbc.queryForMap("""
                    SELECT trace_id, error_code,
                           (SELECT COALESCE(MAX(seq), 0) + 1 FROM outbound_request_state_log
                             WHERE outbound_request_id = ?) AS next_seq
                    FROM outbound_request WHERE id = ?
                    """, id, id);
            insertLog(id, ((Number) cur.get("next_seq")).intValue(), from, "COMPENSATING", 0,
                    (String) cur.get("error_code"), TRIGGER_REPLAY, "死信重放：置回补偿队列（attempt 清零）",
                    (String) cur.get("trace_id"));
        }
        return updated;
    }

    /** 监控页运行记录查询（M4 交付，D-M4-2）：status / bizId / traceId 可空 = 不过滤，倒序分页 */
    public List<OutboundRequestRow> findPaged(String status, String bizId, String traceId, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM outbound_request WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        appendFilter(sql, args, status, bizId, traceId);
        sql.append(" ORDER BY id DESC LIMIT ").append(Math.max(1, limit))
                .append(" OFFSET ").append(Math.max(0, offset));
        return jdbc.query(sql.toString(), OutboundRequestRow.MAPPER, args.toArray());
    }

    /** 监控页计数（与 findPaged 同过滤口径） */
    public long countPaged(String status, String bizId, String traceId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM outbound_request WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        appendFilter(sql, args, status, bizId, traceId);
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private void appendFilter(StringBuilder sql, List<Object> args, String status, String bizId, String traceId) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (bizId != null && !bizId.isBlank()) {
            sql.append(" AND biz_id = ?");
            args.add(bizId);
        }
        if (traceId != null && !traceId.isBlank()) {
            sql.append(" AND trace_id = ?");
            args.add(traceId);
        }
    }

    /** 状态计数（监控统计卡 / AlertWorker retry_backlog 指标） */
    public long countByStatus(String status) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbound_request WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    /** 今日（updated_at 当日）指定状态计数（监控统计卡「今日成功率」分子分母，走 idx_outreq_updated） */
    public long countTodayByStatus(String status) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbound_request
                WHERE status = ? AND updated_at >= CURDATE()
                """, Long.class, status);
        return n == null ? 0 : n;
    }

    /** 近 N 分钟终态成功率（AlertWorker success_rate 指标，走 idx_outreq_updated；无终态返回 -1 表示无样本） */
    public double successRateRecent(int windowMinutes) {
        var result = jdbc.queryForMap("""
                SELECT
                    SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount,
                    SUM(CASE WHEN status IN ('SUCCESS', 'DEAD_LETTER') THEN 1 ELSE 0 END) AS totalCount
                FROM outbound_request
                WHERE status IN ('SUCCESS', 'DEAD_LETTER')
                  AND updated_at >= DATE_SUB(NOW(), INTERVAL ? MINUTE)
                """, windowMinutes);
        long total = result.get("totalCount") == null ? 0 : ((Number) result.get("totalCount")).longValue();
        if (total == 0) {
            return -1; // 无样本：不做告警判断
        }
        long success = result.get("successCount") == null ? 0 : ((Number) result.get("successCount")).longValue();
        return (double) success * 100 / total;
    }

    /** 出站终态按分钟桶计数（仪表盘趋势 FAIL/成功：SUCCESS / DEAD_LETTER / UNKNOWN，走 idx_outreq_updated） */
    public Map<Long, Map<String, Long>> terminalCountsByMinute(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList("""
                SELECT UNIX_TIMESTAMP(updated_at) DIV 60 AS b, status, COUNT(*) AS c
                FROM outbound_request
                WHERE status IN ('SUCCESS', 'DEAD_LETTER', 'UNKNOWN')
                  AND updated_at >= ? AND updated_at < ?
                GROUP BY b, status
                """, Timestamp.valueOf(from), Timestamp.valueOf(to)).stream().collect(Collectors.toMap(
                r -> ((Number) r.get("b")).longValue(),
                r -> Map.of((String) r.get("status"), ((Number) r.get("c")).longValue()),
                (m1, m2) -> {
                    LinkedHashMap<String, Long> merged = new LinkedHashMap<>(m1);
                    m2.forEach((k, v) -> merged.merge(k, v, Long::sum));
                    return merged;
                },
                LinkedHashMap::new));
    }

    /** 出站终态按接口计数（TOP 接口成功率/失败数，同口径） */
    public Map<Long, Map<String, Long>> terminalCountsByInterface(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList("""
                SELECT interface_id, status, COUNT(*) AS c
                FROM outbound_request
                WHERE status IN ('SUCCESS', 'DEAD_LETTER', 'UNKNOWN')
                  AND updated_at >= ? AND updated_at < ?
                GROUP BY interface_id, status
                """, Timestamp.valueOf(from), Timestamp.valueOf(to)).stream().collect(Collectors.toMap(
                r -> ((Number) r.get("interface_id")).longValue(),
                r -> Map.of((String) r.get("status"), ((Number) r.get("c")).longValue()),
                (m1, m2) -> {
                    LinkedHashMap<String, Long> merged = new LinkedHashMap<>(m1);
                    m2.forEach((k, v) -> merged.merge(k, v, Long::sum));
                    return merged;
                },
                LinkedHashMap::new));
    }

    /** 清空 error_code（对账收敛 SUCCESS 时显式清空——updateState 的 COALESCE(null) 不覆盖旧值） */
    public int clearErrorCode(long id) {
        return jdbc.update("UPDATE outbound_request SET error_code = NULL WHERE id = ?", id);
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
