package com.deepx.apicenter.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SnapshotChangeDiff 保护网（版本历史变更说明完善，2026-09-07）：
 * 主字段 leaf old→new / 子表增删改摘要 / 备注组合 / 无差异兜底 / detail JSON 结构。
 * 输入为 SnapshotSerializer 输出的 config_json 形态（main + 五段数组）。
 */
class SnapshotChangeDiffTest {

    private static final String OLD = """
            {"main":{"code":"IF-A","name":"n","method":"POST","path":"/a","protocolIn":"JSON","protocolOut":"JSON",
                      "timeoutMs":3000,"maxRetries":4},
             "params":[{"side":"IN","name":"state","type":"string","required":true}],
             "mappings":[{"source":"state","op":"rename","target":"order_state","param":null,"nullStrategy":"KEEP"}],
             "fieldDefs":[{"kind":"RESP","name":"total","type":"number"}],
             "bindings":[{"role":"MESSAGE","adapterId":"ADP-X","version":"9.1"}],
             "bodies":[{"side":"IN","bodyType":"json","raw":"{\\"a\\":1}","form":null}]}
            """;

    private static final String NEW = """
            {"main":{"code":"IF-A","name":"n2","method":"POST","path":"/a","protocolIn":"JSON","protocolOut":"JSON",
                      "timeoutMs":5000,"maxRetries":4},
             "params":[{"side":"IN","name":"state","type":"string","required":true},
                       {"side":"IN","name":"qty","type":"number","required":false}],
             "mappings":[{"source":"state","op":"rename","target":"order_state","param":null,"nullStrategy":"KEEP"}],
             "fieldDefs":[],
             "bindings":[{"role":"MESSAGE","adapterId":"ADP-Y","version":"9.2"}],
             "bodies":[{"side":"IN","bodyType":"xml","raw":"","form":null}]}
            """;

    @Test
    void 主字段与子表_diff_摘要与结构() {
        SnapshotChangeDiff.DiffResult r = SnapshotChangeDiff.build(OLD, NEW, "放宽超时");
        // 摘要：主字段 + 参数新增 + 响应字段删除 + 绑定切换 + body 类型
        assertThat(r.summary()).contains("名称 n→n2");
        assertThat(r.summary()).contains("读超时(ms) 3000→5000");
        assertThat(r.summary()).contains("参数 1→2（新增 IN/qty）");
        assertThat(r.summary()).contains("响应/ack 字段 1→0（删除 RESP/total）");
        assertThat(r.summary()).contains("MESSAGE ADP-X:9.1→ADP-Y:9.2");
        assertThat(r.summary()).contains("Body(IN) json→xml");
        assertThat(r.summary()).endsWith("｜备注：放宽超时");

        assertThat(r.detailJson()).contains("\"type\":\"UPDATE\"").contains("\"field\":\"timeoutMs\"")
                .contains("\"old\":\"3000\"").contains("\"new\":\"5000\"")
                .contains("\"action\":\"ADD\"").contains("\"action\":\"DEL\"")
                .contains("\"manual\":\"放宽超时\"");
    }

    @Test
    void 主字段超限_截断到3条加等N项() {
        String newJson = NEW.replace("5000", "6000").replace("n2", "n3")
                .replace("\"method\":\"POST\"", "\"method\":\"GET\"")
                .replace("\"path\":\"/a\"", "\"path\":\"/b\"");
        SnapshotChangeDiff.DiffResult r = SnapshotChangeDiff.build(OLD, newJson, null);
        // 变更字段：name/timeoutMs/method/path = 4 项 > 3
        assertThat(r.summary()).contains("等 4 项字段");
        assertThat(r.summary()).doesNotContain("｜备注");
    }

    @Test
    void 无差异_仅备注_与完全无变化() {
        SnapshotChangeDiff.DiffResult noted = SnapshotChangeDiff.build(OLD, OLD, "仅备注");
        assertThat(noted.summary()).isEqualTo("变更：无字段变化｜备注：仅备注");
        SnapshotChangeDiff.DiffResult none = SnapshotChangeDiff.build(OLD, OLD, null);
        assertThat(none.summary()).isEqualTo("变更：无字段变化");
        assertThat(none.detailJson()).contains("\"manual\":\"\"");
    }

    @Test
    void mapping修改与删除_摘要() {
        String delOld = """
                {"main":{"code":"I","method":"POST","path":"/m"},
                 "mappings":[{"source":"a","op":"rename","target":"b","param":null,"nullStrategy":"KEEP"},
                             {"source":"c","op":"rename","target":"d","param":null,"nullStrategy":"KEEP"}]}
                """;
        String updNew = """
                {"main":{"code":"I","method":"POST","path":"/m"},
                 "mappings":[{"source":"a","op":"rename","target":"b","param":"X→Y","nullStrategy":"KEEP"}]}
                """;
        SnapshotChangeDiff.DiffResult r = SnapshotChangeDiff.build(delOld, updNew, null);
        assertThat(r.summary()).contains("字段映射 2→1");
        assertThat(r.summary()).contains("删除 c→d");
        assertThat(r.summary()).contains("修改 1 条");
    }

    @Test
    void mapping增删混合_新增数正确() {
        // 一增一删（同数）：新增数必须为 1（回归 H2 公式错误）
        String oldJ = """
                {"main":{"code":"I","method":"POST","path":"/m"},
                 "mappings":[{"source":"a","op":"rename","target":"b","param":null,"nullStrategy":"KEEP"},
                             {"source":"c","op":"rename","target":"d","param":null,"nullStrategy":"KEEP"}]}
                """;
        String newJ = """
                {"main":{"code":"I","method":"POST","path":"/m"},
                 "mappings":[{"source":"a","op":"rename","target":"b","param":null,"nullStrategy":"KEEP"},
                             {"source":"e","op":"rename","target":"f","param":null,"nullStrategy":"KEEP"}]}
                """;
        SnapshotChangeDiff.DiffResult r = SnapshotChangeDiff.build(oldJ, newJ, null);
        assertThat(r.summary()).contains("字段映射 2→2");
        assertThat(r.summary()).contains("新增 e→f");
        assertThat(r.summary()).contains("删除 c→d");
    }

    @Test
    void 参数sample与body内容改动_产生摘要() {
        String oldJ = """
                {"main":{"code":"I","method":"POST","path":"/m"},
                 "params":[{"side":"IN","name":"p","type":"string","required":true,"sample":"a"}],
                 "bodies":[{"side":"IN","bodyType":"form","raw":null,"form":"[[\"k\",\"v\"]]"}]}
                """;
        String newJ = """
                {"main":{"code":"I","method":"POST","path":"/m"},
                 "params":[{"side":"IN","name":"p","type":"string","required":true,"sample":"b"}],
                 "bodies":[{"side":"IN","bodyType":"form","raw":null,"form":"[[\"k\",\"v2\"]]"}]}
                """;
        SnapshotChangeDiff.DiffResult r = SnapshotChangeDiff.build(oldJ, newJ, null);
        assertThat(r.summary()).contains("参数 1→1（修改 1 条）");
        assertThat(r.summary()).contains("Body(IN) 内容有改动");
    }

    @Test
    void 回滚detail_类型与主字段差异() {
        String detail = SnapshotChangeDiff.rollbackDetail(OLD, NEW);
        assertThat(detail).contains("\"type\":\"ROLLBACK\"");
        assertThat(detail).contains("\"field\":\"timeoutMs\"").contains("\"old\":\"3000\"").contains("\"new\":\"5000\"");
        // ROLLBACK 无手填备注
        assertThat(detail).contains("\"manual\":\"\"");
    }

    @Test
    void 主字段补全与长值截断() {
        String longV = "x".repeat(120);
        String oldJ = "{\"main\":{\"code\":\"A\",\"name\":\"old\",\"ifType\":\"OUTBOUND\",\"method\":\"POST\",\"path\":\"/a\",\"groupId\":1,\"desc\":\"" + longV + "\"}}";
        String newJ = "{\"main\":{\"code\":\"B\",\"name\":\"new\",\"ifType\":\"INBOUND\",\"method\":\"POST\",\"path\":\"/a\",\"groupId\":2,\"desc\":\"" + longV + "x\"}}";
        SnapshotChangeDiff.DiffResult r = SnapshotChangeDiff.build(oldJ, newJ, null);
        assertThat(r.summary()).contains("接口标识 A→B");
        assertThat(r.summary()).contains("接口类型 OUTBOUND→INBOUND");
        assertThat(r.summary()).contains("分组 1→2");
        // 长值截断 + …，且不超上限
        assertThat(r.summary().length()).isLessThan(900);
        assertThat(r.summary()).contains("…");
    }
}
