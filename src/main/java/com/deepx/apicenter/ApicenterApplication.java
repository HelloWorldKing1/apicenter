package com.deepx.apicenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * API 中心入口 —— 三方接口统一调用平台（只做连接 + 适配 + 可靠传输，不承接业务决策）。
 *
 * <p>包结构规划（对应《技术架构和实现方案.md》§2.1，随 M1–M5 逐步落地）：
 * <ul>
 *   <li>controller/ 管理面 REST（应用 / 分组 / 接口 / 监控 / 适配器 5 模块）+ 接入层路由（M1/M2）</li>
 *   <li>service/    业务编排：配置校验、状态机流转（M1）</li>
 *   <li>repository/ 数据访问：JdbcTemplate 直连 SQL，无 JPA（M1）</li>
 *   <li>engine/     执行面：适配器链引擎 + 出站 / 入站执行引擎（M2/M3）</li>
 *   <li>adapter/    适配器实现：鉴权 / 协议 / 报文三类（M2）</li>
 *   <li>mapping/    动态字段映射引擎：6 操作运行时解释器（M2）</li>
 *   <li>client/     通用声明式 HTTP 客户端：动态 URI / 凭证组装（M2）</li>
 *   <li>worker/     补偿 / 对账 worker（M2/M4）</li>
 *   <li>aspect/     AOP 调用日志与脱敏（M4）</li>
 *   <li>config/     配置与 Bean 装配（M1）</li>
 * </ul>
 *
 * @see src/main/resources/doc/开发文档/ M0 契约设计（链引擎 / 映射语义 / 客户端对账）
 */
@SpringBootApplication
@EnableScheduling        // 补偿 / 对账 worker 定时扫描（设计 §6.3）
@EnableResilientMethods  // Spring 7 @Retryable 短重试（M2 出站链路，独立 Invoker 类）
public class ApicenterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApicenterApplication.class, args);
    }
}
