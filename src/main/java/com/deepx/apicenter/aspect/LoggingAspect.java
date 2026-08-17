package com.deepx.apicenter.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * AOP 统一请求日志（设计文档 §7.2，能力 3.3）。
 *
 * <p>拦截 {@code service} 包内所有方法，统一记录：方法签名 / 入参（脱敏）/ 出参 / 耗时 / 异常。
 * 日志中的 traceId 由 OpenTelemetry 自动注入（logback 可配 %traceId 占位），
 * 保证一次调用从 UI → ERP → 组件 → 第三方全程同 traceId。
 */
@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);

    /**
     * 环绕通知：记录耗时与入参出参，异常不吞（继续抛出给上层处理）。
     */
    @Around("execution(* com.deepx.apicenter.service..*(..))")
    public Object logAround(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        String method = pjp.getSignature().toShortString();
        try {
            Object result = pjp.proceed();
            log.info("[trace] {} | 入参={} | 出参={} | 耗时={}ms",
                    method, mask(Arrays.toString(pjp.getArgs())), result, System.currentTimeMillis() - start);
            return result;
        } catch (Throwable ex) {
            log.error("[trace] {} | 入参={} | 异常={} | 耗时={}ms",
                    method, mask(Arrays.toString(pjp.getArgs())), ex.getMessage(), System.currentTimeMillis() - start);
            throw ex;
        }
    }

    /**
     * 敏感串脱敏（设计文档 §7.3）：
     * <ul>
     *   <li>手机号：{@code 138****1234}（保留前 3 后 4）</li>
     *   <li>authorization/secret/token 类键值：值打码为 {@code ****}</li>
     * </ul>
     */
    private String mask(String s) {
        if (s == null) {
            return "";
        }
        String masked = s.replaceAll("(1[3-9]\\d)\\d{4}(\\d{4})", "$1****$2");
        return masked.replaceAll("(?i)(authorization|secret|token)[=:]\\S+", "$1=****");
    }
}
