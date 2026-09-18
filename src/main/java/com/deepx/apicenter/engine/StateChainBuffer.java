package com.deepx.apicenter.engine;

import com.deepx.apicenter.repository.OutboundRequestRepository.StateChainNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求级状态链缓冲（2026-09-18 从 {@code OutboundEngine} 抽出，前置编排 PS-4）。
 *
 * <p>原语义不变：主链路的状态节点在内存攒批、请求出口一次 flush（远程库下「逐节点 SELECT+INSERT」
 * 会显著拖慢主链路，见 OutboundRequestRepository.flushStateChain 的取舍说明）。
 *
 * <p>抽出的唯一原因：{@link PreStepExecutor} 也要写「步骤留痕」节点，而它不是 OutboundEngine 的内部类；
 * 缓冲语义（ThreadLocal / 单请求单线程 / 出口批量落库）与会话纪律完全沿用。
 */
public final class StateChainBuffer {

    private static final ThreadLocal<List<StateChainNode>> BUFFER = new ThreadLocal<>();

    private StateChainBuffer() {
    }

    /** 请求入口调用（execute / replay）：开启本线程的攒批缓冲 */
    public static void begin() {
        BUFFER.set(new ArrayList<>());
    }

    /** 追加节点；无活动缓冲（未开启）时静默忽略（与非链内调用对齐） */
    public static void append(String from, String to, int attempt, String errorCode,
                              String trigger, String detail) {
        List<StateChainNode> buffer = BUFFER.get();
        if (buffer != null) {
            buffer.add(new StateChainNode(from, to, attempt, errorCode, trigger, detail));
        }
    }

    /** 请求出口调用：取出并清理缓冲（返回值恒非 null） */
    public static List<StateChainNode> drain() {
        List<StateChainNode> buffer = BUFFER.get();
        BUFFER.remove();
        return buffer == null ? List.of() : buffer;
    }
}
