package com.deepx.apicenter.worker;

import com.deepx.apicenter.aspect.AccessAuthContext;
import com.deepx.apicenter.repository.AccessAuthLogRepository;
import com.deepx.apicenter.repository.AccessAuthLogRepository.AccessAuthLogEntry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * `access_auth_log` 异步批量写（2026-09-23，入站鉴权 B3；设计方案 §10.1）——**与 {@code CallLogWriter} 同构**：
 * 主链路只非阻塞 `offer`（有界队列 1000，满则丢弃 + Counter + warn —— 审计可容忍极端丢失，**不阻塞主链路**）；
 * 单消费线程攒 50 条或 1000ms 批量 `batchUpdate`；`@PreDestroy` 尽力冲刷余量。
 *
 * <p>调用点只有一个：网关切面 `finally`（读 {@link AccessAuthContext} → offer → clear），
 * 与 `call_log` 的清理契约完全一致（防 ThreadLocal 泄漏）。
 */
@Component
public class AccessAuthLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AccessAuthLogWriter.class);

    private static final int QUEUE_CAPACITY = 1000;
    private static final int BATCH_SIZE = 50;
    private static final long FLUSH_INTERVAL_MILLIS = 1000;

    private final AccessAuthLogRepository repository;
    private final BlockingQueue<AccessAuthLogEntry> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Counter droppedCounter;
    private final Thread worker;
    private volatile boolean running = true;

    public AccessAuthLogWriter(AccessAuthLogRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.droppedCounter = meterRegistry.counter("apicenter.accessauth.dropped");
        worker = new Thread(this::drainLoop, "access-auth-log-writer");
        worker.setDaemon(true);
        worker.start();
    }

    /** 主链路投递（非阻塞）：队列满即丢弃（可观测，不阻塞不重试） */
    public void offer(AccessAuthLogEntry entry) {
        if (!queue.offer(entry)) {
            long total = dropped.incrementAndGet();
            droppedCounter.increment();
            log.warn("access_auth_log 写入队列已满，丢弃第 {} 条（traceId={}）", total, entry.traceId());
        }
    }

    /** 队列当前长度（测试 / 观测用） */
    public int pending() {
        return queue.size();
    }

    /** 测试与停机用：阻塞式冲刷（把当前队列写完再返回） */
    public void flushNow() {
        List<AccessAuthLogEntry> rest = new ArrayList<>(queue.size());
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            flushQuietly(rest, "flushNow");
        }
    }

    private void drainLoop() {
        List<AccessAuthLogEntry> batch = new ArrayList<>(BATCH_SIZE);
        while (running) {
            try {
                AccessAuthLogEntry first = queue.poll(FLUSH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                int size = batch.size();   // flush 内部会 clear，事后取 size 恒 0（照 CallLogWriter 的备注）
                try {
                    repository.insertBatch(new ArrayList<>(batch));
                } catch (Exception e) {
                    log.error("access_auth_log 批量落库失败，丢弃本批 {} 条", size, e);
                } finally {
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!batch.isEmpty()) {
            flushQuietly(batch, "消费线程退出");
        }
    }

    private void flushQuietly(List<AccessAuthLogEntry> batch, String scene) {
        try {
            repository.insertBatch(new ArrayList<>(batch));
        } catch (Exception e) {
            log.warn("{}：冲刷 access_auth_log 余量失败，丢弃 {} 条", scene, batch.size());
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
        List<AccessAuthLogEntry> rest = new ArrayList<>(queue.size());
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            flushQuietly(rest, "停机");
        }
    }
}
