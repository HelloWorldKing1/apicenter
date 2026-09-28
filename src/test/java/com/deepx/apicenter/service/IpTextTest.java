package com.deepx.apicenter.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 来源 IP 归一化单测（2026-09-25）。
 *
 * <p>起缘：验收时审计表显示 `来源 IP = 0:0:0:0:0:0:0:1`（JDK 对 IPv6 回环的**展开形式**），
 * 而运维在 IP 名单 / 审计「按 IP 筛」里手写的是 `::1` ⇒ 精确匹配不命中 ⇒ 误判为「白名单不生效 / 筛不出来」。
 */
class IpTextTest {

    @Test
    void IPv6回环_展开形式归一化为压缩形式() {
        assertThat(IpText.canonical("0:0:0:0:0:0:0:1")).isEqualTo("::1");
        assertThat(IpText.canonical("::1")).isEqualTo("::1");
        assertThat(IpText.canonical("  0:0:0:0:0:0:0:1  ")).isEqualTo("::1");
        assertThat(IpText.canonical("0:0:0:0:0:0:0:0")).isEqualTo("::");
        assertThat(IpText.canonical("::")).isEqualTo("::");
    }

    @Test
    void IPv6常规地址_去前导零_最长零串压缩且并列取最左() {
        assertThat(IpText.canonical("2001:0DB8:0000:0000:0000:0000:0000:0001")).isEqualTo("2001:db8::1");
        assertThat(IpText.canonical("2001:db8:0:1:1:1:1:1")).isEqualTo("2001:db8:0:1:1:1:1:1"); // 单组零不压缩
        assertThat(IpText.canonical("1:0:0:2:0:0:0:3")).isEqualTo("1:0:0:2::3");                 // 最长那串中选
        assertThat(IpText.canonical("fe80::1")).isEqualTo("fe80::1");
    }

    @Test
    void IPv4映射_归一为点分四段() {
        assertThat(IpText.canonical("::ffff:127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(IpText.canonical("::FFFF:10.0.0.7")).isEqualTo("10.0.0.7");
        assertThat(IpText.canonical("0:0:0:0:0:0:ffff:127.0.0.1")).isEqualTo("127.0.0.1");
    }

    @Test
    void 作用域后缀与IPv4原样保留() {
        assertThat(IpText.canonical("fe80::1%eth0")).isEqualTo("fe80::1%eth0");
        assertThat(IpText.canonical("127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(IpText.canonical("10.1.2.3")).isEqualTo("10.1.2.3");
        assertThat(IpText.canonical(null)).isNull();
        assertThat(IpText.canonical("  ")).isEmpty();
    }

    @Test
    void 无法解析的文本_原样返回不抛异常() {
        assertThat(IpText.canonical("主机名:8080")).isEqualTo("主机名:8080");
        assertThat(IpText.canonical("1:2:3")).isEqualTo("1:2:3");              // 组数不足
        assertThat(IpText.canonical("1:2:3:4:5:6:7:8:9")).isEqualTo("1:2:3:4:5:6:7:8:9");
        assertThat(IpText.canonical("1::2::3")).isEqualTo("1::2::3");          // 两个 '::'
        assertThat(IpText.canonical("gggg::1")).isEqualTo("gggg::1");
    }

    @Test
    void same_两种写法视为同一IP() {
        assertThat(IpText.same("::1", "0:0:0:0:0:0:0:1")).isTrue();
        assertThat(IpText.same("127.0.0.1", "::ffff:127.0.0.1")).isTrue();
        assertThat(IpText.same("1.2.3.4", "1.2.3.5")).isFalse();
        assertThat(IpText.same(null, "1.2.3.4")).isFalse();
        assertThat(IpText.same(null, null)).isTrue();
    }

    @Test
    void listContains_名单怎么写都能命中() {
        // 名单写压缩形式、来客是展开形式（真实场景：运维写 ::1，套接字给 0:0:0:0:0:0:0:1）
        assertThat(IpText.listContains("127.0.0.1, ::1", "0:0:0:0:0:0:0:1")).isTrue();
        // 名单写展开形式、来客是压缩形式
        assertThat(IpText.listContains("0:0:0:0:0:0:0:1", "::1")).isTrue();
        assertThat(IpText.listContains(" ::ffff:127.0.0.1 ", "127.0.0.1")).isTrue();
        assertThat(IpText.listContains("1.2.3.4", "1.2.3.5")).isFalse();
        assertThat(IpText.listContains("", "1.2.3.4")).isFalse();
        assertThat(IpText.listContains("1.2.3.4", "")).isFalse();
        assertThat(IpText.listContains(null, "1.2.3.4")).isFalse();
    }
}
