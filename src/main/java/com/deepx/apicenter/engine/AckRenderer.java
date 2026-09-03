package com.deepx.apicenter.engine;

import com.deepx.apicenter.adapter.protocol.JsonProtocolAdapter;
import com.deepx.apicenter.adapter.protocol.XmlProtocolAdapter;
import com.deepx.apicenter.client.OutboundRequestSpec;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AppRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.model.InterfaceRow.FieldDefRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.List;

/**
 * ack 回执渲染（D-M3-3 字段名可配、值固定）：
 * interface_field_def（kind=ACK）按 sort_order 排序——第 1 个字段 = code（值 0）、
 * 第 2 个 = message（值 "success"），其余声明忽略 + warning；type 决定数值写法（number → 0，string → "0"）；
 * ACK 无声明 → 兜底平台统一信封 {code:0, msg:"ok", data:null}。
 * 渲染格式按 protocol_in 推导（JSON 回调 → JSON ack / XML 回调 → XML ack，复用协议适配器 ENCODE；
 * XML 根元素取约定名 response）。
 */
@Component
public class AckRenderer {

    private static final Logger log = LoggerFactory.getLogger(AckRenderer.class);

    private final InterfaceRepository interfaceRepository;
    private final AppRepository appRepository;
    private final XmlProtocolAdapter xmlProtocolAdapter;
    private final ObjectMapper objectMapper;

    public AckRenderer(InterfaceRepository interfaceRepository,
                       AppRepository appRepository,
                       XmlProtocolAdapter xmlProtocolAdapter,
                       ObjectMapper objectMapper) {
        this.interfaceRepository = interfaceRepository;
        this.appRepository = appRepository;
        this.xmlProtocolAdapter = xmlProtocolAdapter;
        this.objectMapper = objectMapper;
    }

    /** 渲染 ack 响应（裸报文出口，不走平台统一信封） */
    public ResponseEntity<byte[]> render(InterfaceRow iface, UnifiedModel payload) {
        List<FieldDefRow> defs = interfaceRepository.findFieldDefs(iface.id()).stream()
                .filter(d -> "ACK".equals(d.kind()))
                .sorted(Comparator.comparingInt(FieldDefRow::sortOrder))
                .toList();

        UnifiedModel.UNode root;
        if (defs.size() >= 2) {
            UnifiedModel.ObjectNode node = UnifiedModel.ObjectNode.of();
            FieldDefRow codeDef = defs.get(0);
            FieldDefRow msgDef = defs.get(1);
            node.fields().put(codeDef.name(), "number".equalsIgnoreCase(codeDef.type())
                    ? UnifiedModel.ScalarNode.num(0L)
                    : UnifiedModel.ScalarNode.str("0"));
            node.fields().put(msgDef.name(), UnifiedModel.ScalarNode.str("success"));
            if (defs.size() > 2) {
                log.warn("接口 {} 的 ACK 字段超过 2 个，其余声明忽略：{}", iface.code(),
                        defs.subList(2, defs.size()).stream().map(FieldDefRow::name).toList());
            }
            root = node;
        } else {
            UnifiedModel.ObjectNode node = UnifiedModel.ObjectNode.of();
            node.fields().put("code", UnifiedModel.ScalarNode.num(0L));
            node.fields().put("msg", UnifiedModel.ScalarNode.str("ok"));
            node.fields().put("data", UnifiedModel.ScalarNode.nullNode());
            root = node;
        }

        if ("XML".equals(iface.protocolIn())) {
            AdapterContext ctx = AdapterContext.create(ChainPhase.ENCODE, UnifiedModel.of(root),
                    AdapterContext.InterfaceMeta.of(iface),
                    new AdapterContext.AppMeta(iface.appId(),
                            appRepository.findById(iface.appId()).map(AppRow::baseUrl).orElse("")),
                    new AdapterContext.TraceMeta(null), null, new OutboundRequestSpec());
            ctx.attrs().put("xmlRoot", XmlProtocolAdapter.ROOT_RESPONSE);
            xmlProtocolAdapter.process(ctx);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(ctx.outbound().body());
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(JsonProtocolAdapter.fromUnified(root, objectMapper));
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
        } catch (Exception e) {
            throw new BizException(50000, "ack 回执编码失败：" + e.getMessage());
        }
    }
}
