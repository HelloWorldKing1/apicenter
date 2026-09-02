package com.deepx.apicenter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 冒烟测试：验证 Spring 上下文可加载（需 MySQL/PolarDB 可达、8080 端口空闲）。
 */
@SpringBootTest
class ApicenterApplicationTests {

    @Test
    void contextLoads() {
    }
}
