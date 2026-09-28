package com.deepx.apicenter.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 来源 IP 文本规范化（2026-09-25）。**写入与比较都走这里**，口径只此一份。
 *
 * <h3>为什么需要</h3>
 * `request.getRemoteAddr()` 对 IPv6 回环返回的是 **JDK 的展开形式** `0:0:0:0:0:0:0:1`
 * （OpenJDK 不做 RFC 5952 压缩），于是审计表里就长那样；而运维在白名单 / 审计「按 IP 筛」里
 * 手写的是 `::1` —— IP 名单匹配与筛选都是**精确字符串比较**（`equals`）⇒ **必然不命中**，
 * 表现为「明明加了白名单/明明按 IP 筛了，却 40103 / 查不到」（极易误判为功能坏了）。
 *
 * <h3>口径</h3>
 * <ol>
 *   <li>去首尾空白；IPv4 与主机名**原样返回**；</li>
 *   <li>`::ffff:a.b.c.d` / `0:0:0:0:0:0:ffff:a.b.c.d`（IPv4 映射，回环/双栈常见）⇒ `a.b.c.d`；</li>
 *   <li>IPv6 归一为 **RFC 5952** 形式：小写、去前导零、**最长零串（≥2 组，并列取最左）**压成 `::`；</li>
 *   <li>`%scope`（如 `fe80::1%eth0`）原样保留（链路本地需要接口名才唯一）；</li>
 *   <li>解析失败（非法文本 / 主机名里带冒号）⇒ **原样返回，不抛异常**（别为格式问题打死请求）。</li>
 * </ol>
 *
 * <p>⚠️ 原始报文里的 `X-Forwarded-For` 整串仍**原样**记入 `xff_chain`（取证用），不归一化。
 */
public final class IpText {

    private IpText() {
    }

    /** 规范化：返回可用于**落库 / 展示 / 比较**的 IP 文本（无法解析时原样返回） */
    public static String canonical(String ip) {
        if (ip == null) {
            return null;
        }
        String s = ip.trim();
        if (s.isEmpty() || !s.contains(":")) {
            return s;   // IPv4（127.0.0.1）/ 主机名：原样
        }
        int pct = s.indexOf('%');
        String scope = pct < 0 ? "" : s.substring(pct);
        String head = (pct < 0 ? s : s.substring(0, pct)).toLowerCase();

        // ① 文本形态 `::ffff:a.b.c.d` / `::a.b.c.d`（最常出现在 TCP 套接字上）
        String dotted = dottedTailOf(head);
        if (dotted != null) {
            return dotted + scope;
        }
        // ② 通用展开 + 压缩（含 `0:0:0:0:0:0:ffff:1.2.3.4` 这种全展开的映射形式）
        int[] groups = expand(head);
        if (groups == null) {
            return s;   // 解析失败：原样（保留大小写与 scope）
        }
        if (isIpv4Mapped(groups)) {
            return v4Of(groups[6], groups[7]) + scope;
        }
        return compress(groups) + scope;
    }

    /** 两个 IP 是否等价（规范化后比较；null 只与 null 相等） */
    public static boolean same(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return Objects.equals(canonical(a), canonical(b));
    }

    /**
     * 逗号分隔名单是否包含该 IP（空白容忍；**不区分 `::1` 与 `0:0:0:0:0:0:0:1`**；不支持 CIDR，v1.1）。
     * 名单与来客 IP 都先规范化 ⇒ 运维怎么写都能命中。
     */
    public static boolean listContains(String csv, String ip) {
        if (csv == null || csv.isBlank() || ip == null || ip.isBlank()) {
            return false;
        }
        for (String item : csv.split(",")) {
            String one = item.trim();
            if (!one.isEmpty() && same(one, ip)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 内部 ----------

    /** `::ffff:a.b.c.d` / `::a.b.c.d` → `a.b.c.d`（仅这两种文本形态；其余走通用展开） */
    private static String dottedTailOf(String head) {
        int lastColon = head.lastIndexOf(':');
        if (lastColon < 0 || head.indexOf('.', lastColon) < 0) {
            return null;
        }
        String prefix = head.substring(0, lastColon).replaceAll(":+$", "");
        if (!prefix.isEmpty() && !"0:0:0:0:0:0:ffff".equals(prefix)) {
            return null;
        }
        String quad = head.substring(lastColon + 1);
        return parseIpv4(quad) == null ? null : quad;
    }

    /** 展开为 8 个 16 位组；不合法返回 null（不抛） */
    private static int[] expand(String head) {
        String s = head;
        // 尾部点分四段 → 等价的十六进制组（这样后面只剩「十六进制组 + 冒号」一种形态）
        int lastColon = s.lastIndexOf(':');
        if (lastColon >= 0 && s.indexOf('.', lastColon) > lastColon) {
            int[] quad = parseIpv4(s.substring(lastColon + 1));
            if (quad == null) {
                return null;
            }
            s = s.substring(0, lastColon + 1) + Integer.toHexString((quad[0] << 8) | quad[1])
                    + ":" + Integer.toHexString((quad[2] << 8) | quad[3]);
        }
        int doubleColon = s.indexOf("::");
        if (doubleColon != s.lastIndexOf("::")) {
            return null;   // 只允许出现一次 '::'
        }
        String left = doubleColon < 0 ? s : s.substring(0, doubleColon);
        String right = doubleColon < 0 ? "" : s.substring(doubleColon + 2);
        List<Integer> headGroups = parseGroups(left);
        List<Integer> tailGroups = parseGroups(right);
        if (headGroups == null || tailGroups == null) {
            return null;
        }
        if (doubleColon < 0 && headGroups.size() != 8) {
            return null;
        }
        if (doubleColon >= 0 && headGroups.size() + tailGroups.size() > 7) {
            return null;   // '::' 至少要代表 1 个零组
        }
        List<Integer> all = new ArrayList<>(headGroups);
        while (all.size() < 8 - tailGroups.size()) {
            all.add(0);
        }
        all.addAll(tailGroups);
        if (all.size() != 8) {
            return null;
        }
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) {
            out[i] = all.get(i);
        }
        return out;
    }

    /** 冒号分隔的组（允许空串：`1::2` 切出来会有空段，交由 '::' 逻辑处理）；不合法返回 null */
    private static List<Integer> parseGroups(String text) {
        List<Integer> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        for (String part : text.split(":", -1)) {
            if (part.isEmpty()) {
                return null;   // 空段只允许出现在 '::' 处
            }
            if (part.length() > 4) {
                return null;
            }
            try {
                out.add(Integer.parseInt(part, 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /** IPv4 映射：`::ffff:a.b.c.d` 展开后 = 组 0..4 全 0、**组 5 = 0xffff**，后两组即 IPv4 */
    private static boolean isIpv4Mapped(int[] g) {
        for (int i = 0; i < 5; i++) {
            if (g[i] != 0) {
                return false;
            }
        }
        return g[5] == 0xffff;
    }

    private static String v4Of(int hi, int lo) {
        return ((hi >> 8) & 0xff) + "." + (hi & 0xff) + "." + ((lo >> 8) & 0xff) + "." + (lo & 0xff);
    }

    private static int[] parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return null;
            }
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                out[i] = v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /** RFC 5952 压缩：小写、去前导零、最长零串（≥2 组，并列取最左）压成 `::` */
    private static String compress(int[] g) {
        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (g[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && g[j] == 0) {
                j++;
            }
            if (j - i > bestLen) {
                bestLen = j - i;
                bestStart = i;
            }
            i = j;
        }
        if (bestLen < 2) {
            bestStart = -1;   // 单个零组不压缩（RFC 5952）
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; ) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen;
                continue;
            }
            sb.append(Integer.toHexString(g[i]));
            i++;
            if (i < 8 && i != bestStart) {
                sb.append(':');
            }
        }
        return sb.toString();
    }
}
