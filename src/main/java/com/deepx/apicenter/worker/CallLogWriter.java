package com.deepx.apicenter.worker;

import com.deepx.apicenter.repository.CallLogRepository;
import com.deepx.apicenter.repository.CallLogRepository.CallLogEntry;
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
 * call_log 异步批量写（M4 交付，D-M4-4；M5 压测验证不阻塞主链路）：
 * 主链路只非阻塞 offer（有界队列 1000，满则丢弃 + Counter + log.warn——监控数据可容忍丢失，
 * 不重试不阻塞）；单消费线程攒 50 条或 1000ms 批量 batchUpdate；@PreDestroy 尽力 flush 余量。
 */
@Component
public class CallLogWriter {

    private static final Logger log = LoggerFactory.getLogger(CallLogWriter.class);

    private static final int QUEUE_CAPACITY = 1000;
    private static final int BATCH_SIZE = 50;
    private static final long FLUSH_INTERVAL_MILLIS = 1000;

    private final CallLogRepository callLogRepository;
    private final BlockingQueue<CallLogEntry> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    public CallLogWriter(CallLogRepository callLogRepository, MeterRegistry meterRegistry) {
        this.callLogRepository = callLogRepository;
        meterRegistry.counter("apicenter.calllog.dropped");
        worker = new Thread(this::drainLoop, "call-log-writer");
        worker.setDaemon(true);
        worker.start();
    }

    /** 主链路投递（非阻塞）：队列满即丢弃（可观测，不阻塞不重试） */
    public void offer(CallLogEntry entry) {
        if (!queue.offer(entry)) {
            long total = dropped.incrementAndGet();
            log.warn("call_log 写入队列已满，丢弃第 {} 条（traceId={}）", total, entry.traceId());
        }
    }

    /** 队列当前长度（测试 / 观测用） */
    public int pending() {
        return queue.size();
    }

    private void drainLoop() {
        List<CallLogEntry> batch = new ArrayList<>(BATCH_SIZE);
        while (running) {
            try {
                CallLogEntry first = queue.poll(FLUSH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 批次异常隔离：丢弃本批（监控数据可容忍），继续消费后续批次
                log.error("call_log 批量落库失败，丢弃本批 {} 条", batch.size(), e);
                batch.clear();
            }
        }
        // 退出前尽力冲刷
        if (!batch.isEmpty()) {
            flushQuietly(batch);
        }
    }

    private void flush(List<CallLogEntry> batch) {
        try {
            callLogRepository.insertBatch(new ArrayList<>(batch));
        } finally {
            batch.clear();
        }
    }

    private void flushQuietly(List<CallLogEntry> batch) {
        try {
            callLogRepository.insertBatch(new ArrayList<>(batch));
        } catch (Exception e) {
            log.warn("停机冲刷 call_log 余量失败，丢弃 {} 条", batch.size());
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
        // 尽力冲刷剩余（daemon 线程可能在 JVM 退出前未跑完 drainLoop 尾部）
        List<CallLogEntry> rest = new ArrayList<>(queue.size());
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            flushQuietly(rest);
        }
    }
}
