package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.model.InterfaceRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 快照序列化器保护网（D-M5-1 定稿内容即契约）：config_json ↔ 六段（main/params/bodies/mappings/fieldDefs/bindings）
 * 序列化往返等价 / 空子表容忍 / 未知字段忽略（向前兼容，防结构漂移——未来加子表必须回改序列化器并更新本测试）。
 */
class SnapshotSerializerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SnapshotSerializer serializer = new SnapshotSerializer(mapper);

    @Test
    void 往返等价_六段内容_空子表与全量子表() throws Exception {
        // 空子表往返
        InterfaceRow empty = row("IF-X", 2);
        String jsonEmpty = serializer.toJson(empty, List.of(), List.of(), List.of(), List.of(), List.of());
        InterfaceRequest reqEmpty = serializer.toRequest(jsonEmpty, 5);
        assertThat(reqEmpty.params()).isEmpty();
        assertThat(reqEmpty.bindings()).isEmpty();
        assertThat(reqEmpty.mappings()).isEmpty();
        assertThat(reqEmpty.fieldDefs()).isEmpty();
        assertThat(reqEmpty.appId()).isEqualTo("M5-APP");
        assertThat(reqEmpty.version()).isEqualTo(5); // 乐观锁版本由调用方传入
        assertThat(reqEmpty.status()).isNull(); // 快照不含 status（回滚保留当前生命周期）

        // 全量六段往返：序列化 → 反序列化 → 再按请求重建行 → 再序列化，语义相等
        InterfaceRow full = row("IF-GR", 3);
        String json1 = serializer.toJson(full,
                List.of(new InterfaceRow.ParamRow(0, "IN", "state", "string", true, "PAID", 1),
                        new InterfaceRow.ParamRow(0, "OUT", "order_state", "string", true, null, 1)),
                List.of(new InterfaceRow.BodyRow(0, "IN", "json", "{\"a\":1}", null)),
                List.of(new InterfaceRow.MappingRow(0, "state", "rename", "order_state", null, "KEEP", 1),
                        new InterfaceRow.MappingRow(0, null, "default", "fixed", "v1", "KEEP", 2)),
                List.of(new InterfaceRow.FieldDefRow(0, "RESP", "total", "number", "总数", 1)),
                List.of(new InterfaceRow.BindingRow(0, "MESSAGE", "ADP-X", "9.1")));

        InterfaceRequest parsed = serializer.toRequest(json1, 7);
        // 重建行必须由 parsed（main 快照）构造：校验含 appId / callbackUrl——换过应用的接口回滚不恢复错归属
        assertThat(parsed.appId()).isEqualTo("M5-APP");
        assertThat(parsed.groupId()).isEqualTo(11L);
        assertThat(parsed.timeoutMs()).isEqualTo(3000);
        assertThat(parsed.mappings()).hasSize(2);
        assertThat(parsed.mappings().get(0).target()).isEqualTo("order_state");
        assertThat(parsed.mappings().get(1).op()).isEqualTo("default");
        assertThat(parsed.mappings().get(1).param()).isEqualTo("v1"); // default 常量
        assertThat(parsed.bindings().get(0).version()).isEqualTo("9.1");
        assertThat(parsed.params()).hasSize(2);
        assertThat(parsed.fieldDefs().get(0).kind()).isEqualTo("RESP");
        assertThat(parsed.bodies().get(0).bodyType()).isEqualTo("json");

        InterfaceRow rebuilt = new InterfaceRow(9, parsed.code(), parsed.name(), parsed.ifType(), parsed.method(), parsed.path(),
                parsed.protocolIn() == null || parsed.protocolIn().isBlank() ? "JSON" : parsed.protocolIn(),
                parsed.protocolOut() == null || parsed.protocolOut().isBlank() ? "JSON" : parsed.protocolOut(),
                parsed.appId(), parsed.groupId(), parsed.upstreamPath(), parsed.callbackUrl(), "PUBLISHED", 3,
                parsed.timeoutMs() == null ? 3000 : parsed.timeoutMs(),
                parsed.maxRetries() == null ? 4 : parsed.maxRetries(), parsed.desc(), null, null, null, null);

        String json2 = serializer.toJson(rebuilt,
                parsed.params().stream().map(p -> new InterfaceRow.ParamRow(0, p.side(), p.name(),
                                p.type() == null ? "string" : p.type(), Boolean.TRUE.equals(p.required()),
                                p.sample(), p.sortOrder() == null ? 0 : p.sortOrder())).toList(),
                parsed.bodies().stream().map(b -> new InterfaceRow.BodyRow(0, b.side(),
                        b.bodyType() == null ? "none" : b.bodyType(), b.raw(), b.form())).toList(),
                parsed.mappings().stream().map(m -> new InterfaceRow.MappingRow(0, m.source(), m.op(),
                        m.target(), m.param(), m.nullStrategy() == null ? "KEEP" : m.nullStrategy(),
                        m.sortOrder() == null ? 0 : m.sortOrder())).toList(),
                parsed.fieldDefs().stream().map(f -> new InterfaceRow.FieldDefRow(0, f.kind(), f.name(),
                        f.type() == null ? "string" : f.type(), f.desc(), f.sortOrder() == null ? 0 : f.sortOrder())).toList(),
                parsed.bindings().stream().map(b -> new InterfaceRow.BindingRow(0, b.role(), b.adapterId(), b.version())).toList());
        assertTreeEqual(json1, json2);
    }

    @Test
    void 未知字段忽略_数组缺省容忍() {
        // 未来加字段（向前兼容）：老快照缺新键 → 容忍；加未知键 → 忽略
        String json = """
                {"main":{"code":"IF-A","name":"n","ifType":"OUTBOUND","method":"POST","path":"/a",
                          "protocolIn":"JSON","protocolOut":"XML","upstreamPath":"/up","appId":"M5-APP",
                          "groupId":3,"timeoutMs":2000,"maxRetries":2},"future":"ignored"}
                """;
        InterfaceRequest req = serializer.toRequest(json, 2);
        assertThat(req.code()).isEqualTo("IF-A");
        assertThat(req.protocolOut()).isEqualTo("XML");
        assertThat(req.groupId()).isEqualTo(3L);
        assertThat(req.callbackUrl()).isNull();
        assertThat(req.params()).isEmpty();
        assertThat(req.mappings()).isEmpty();
        assertThat(req.timeoutMs()).isEqualTo(2000);
    }

    @Test
    void 缺省默认值容差_协议与超时() {
        String json = """
                {"main":{"code":"IF-B","name":"n","ifType":"INBOUND","method":"POST","path":"/b",
                          "appId":"M5-APP","groupId":1,"callbackUrl":"http://localhost:18080/cb"}}
                """;
        InterfaceRequest req = serializer.toRequest(json, 1);
        assertThat(req.protocolIn()).isEqualTo(""); // 显式存了空串；入库时服务层归一化为 JSON
        assertThat(req.timeoutMs()).isEqualTo(3000);
        assertThat(req.maxRetries()).isEqualTo(4);
        assertThat(req.appId()).isEqualTo("M5-APP");
    }

    private InterfaceRow row(String code, int version) {
        return new InterfaceRow(9, code, "演示", "OUTBOUND", "POST", "/m5/" + code,
                "JSON", "JSON", "M5-APP", 11,
                "/upstream", null, "PUBLISHED", version,
                3000, 4, "M5 演示", null, null, null, null);
    }

    private void assertTreeEqual(String a, String b) throws Exception {
        JsonNode ta = mapper.readTree(a);
        JsonNode tb = mapper.readTree(b);
        assertThat(ta).as("config_json 往返语义等价").isEqualTo(tb);
    }
}
