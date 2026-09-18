package com.deepx.apicenter.engine;

import com.deepx.apicenter.exception.BizException;

/**
 * 前置步骤失败（前置编排 D-PS-3 / 设计方案 §6.4 失败传播矩阵）。
 *
 * <p>由 {@link PreStepExecutor} 抛出，**必须由 {@code OutboundEngine} 捕获并分类**——因为前置是挂在
 * {@code ChainEngine} 的 MAPPING 闭包内抛出的，而 {@code doInvoke} 里的 {@code chainEngine.execute(...)}
 * 位于其 try/catch 之外：不补这一处捕获，异常会直接冒到 {@code execute()} 的 {@code catch(BizException)}
 * 原样透出，A 的记录停在 INIT，COMPENSATING / UNKNOWN 两个出口永远走不到（v0.1.2 评审修正点 ①）。
 *
 * <p>kind → 出口（复用既有错误码段，不新增状态与错误码）：
 * <ul>
 *   <li>{@code CONFIG_ERROR} / {@code DEPTH_EXCEEDED} / {@code HTTP_4XX} / {@code BUSINESS_FAIL}
 *       → **链失败出口**（A 不推进状态机，记录停留 INIT；响应 40001，文案带步骤名与原因）；</li>
 *   <li>{@code HTTP_5XX} / {@code CIRCUIT_OPEN} → A 转 COMPENSATING 顺延（50201 / 50202，不 incrementAttempt）；</li>
 *   <li>{@code TIMEOUT} → A 必须转 UNKNOWN（50401）——结果不确定，绝不能被降级成「可安全重试」。</li>
 * </ul>
 */
public class PreStepFailure extends BizException {

    public enum Kind {
        /** 目标缺失 / 非出站中转 / 未发布 / 归属应用异常 —— 配置类错误 */
        CONFIG_ERROR,
        /** 运行期嵌套深度超限（防手工改库绕过保存期校验） */
        DEPTH_EXCEEDED,
        /** 2xx 但信封判定为业务失败 */
        BUSINESS_FAIL,
        /** 4xx（非 429）：上游明确拒绝 */
        HTTP_4XX,
        /** 5xx / 429 短重试耗尽 */
        HTTP_5XX,
        /** 读超时 / 连接异常：结果不确定 */
        TIMEOUT,
        /** 目标接口熔断 OPEN（不触达上游） */
        CIRCUIT_OPEN
    }

    private final Kind kind;
    private final String stepCode;
    private final String detail;

    public PreStepFailure(Kind kind, int code, String stepCode, String detail) {
        super(code, buildMessage(stepCode, detail));
        this.kind = kind;
        this.stepCode = stepCode;
        this.detail = detail;
    }

    public Kind getKind() {
        return kind;
    }

    public String getStepCode() {
        return stepCode;
    }

    public String getDetail() {
        return detail;
    }

    private static String buildMessage(String stepCode, String detail) {
        return "前置步骤 " + (stepCode == null ? "-" : stepCode) + " 失败：" + detail;
    }
}
