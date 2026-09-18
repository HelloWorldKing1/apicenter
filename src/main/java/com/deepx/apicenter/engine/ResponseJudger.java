package com.deepx.apicenter.engine;

import com.deepx.apicenter.adapter.message.EnvelopeMessageAdapter;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.InterfaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 出站响应判定器（2026-09-18 从 {@code OutboundEngine} 抽出，前置编排 PS-4）：
 * 「信封适配判业务成败（M0-03 C2）+ RESP 白名单过滤与类型转换（D-M3-3）」的**唯一实现**。
 *
 * <p>抽出的原因：前置步骤要复用**同一条**判定路径（不能给前置另写一套成功率/成败口径），
 * 而原实现是 {@code OutboundEngine} 的私有方法（{@code envelopeParamsOf} / {@code respDefs} / {@code parseResponse}）。
 * 抽取后 {@code OutboundEngine.handleSuccess} 与 {@link PreStepExecutor} 共用本类，语义与抽取前逐字节一致。
 */
@Component
public class ResponseJudger {

    private static final Logger log = LoggerFactory.getLogger(ResponseJudger.class);

    private final ChainEngine chainEngine;
    private final InterfaceRepository interfaceRepository;
    private final EnvelopeMessageAdapter envelopeMessageAdapter;

    public ResponseJudger(ChainEngine chainEngine,
                          InterfaceRepository interfaceRepository,
                          EnvelopeMessageAdapter envelopeMessageAdapter) {
        this.chainEngine = chainEngine;
        this.interfaceRepository = interfaceRepository;
        this.envelopeMessageAdapter = envelopeMessageAdapter;
    }

    /**
     * 判定 2xx 响应。
     *
     * @param data 过滤后的业务数据（Noop 直通 / 无绑定 = 整个响应体根；信封成功 = 信封内业务数据）
     */
    public record Judged(boolean success, String code, String msg, UnifiedModel.UNode data) {
    }

    /** 按接口的 MESSAGE 绑定与 RESP 声明判定成败并收敛字段（宽松：解码失败按空对象处理） */
    public Judged judge(InterfaceRow iface, byte[] respBody) {
        UnifiedModel respModel = parseResponse(iface.id(), respBody);
        JsonNode envelopeParams = envelopeParamsOf(iface);
        if (envelopeParams == null) {
            // 直通报文适配器（Noop）：业务成败 = HTTP 状态（已 2xx），整个响应体即业务数据
            return new Judged(true, null, null,
                    RespFieldFilter.filter(respModel.root(), respDefs(iface), new ArrayList<>()));
        }
        EnvelopeMessageAdapter.EnvelopeResult envelope =
                envelopeMessageAdapter.adaptResponse(respModel, envelopeParams);
        if (!envelope.success()) {
            return new Judged(false, envelope.code(), envelope.msg(), null);
        }
        return new Judged(true, envelope.code(), envelope.msg(),
                RespFieldFilter.filter(envelope.bizData(), respDefs(iface), new ArrayList<>()));
    }

    // ---------- 私有（原 OutboundEngine 同款实现） ----------

    /** 响应协议解码（D-M3-4 收敛）：按 protocol_out 走协议适配器 DECODE；失败按空对象处理（宽松） */
    private UnifiedModel parseResponse(long interfaceId, byte[] body) {
        try {
            return chainEngine.decodeResponse(interfaceId, body);
        } catch (BizException e) {
            log.warn("响应报文解析失败（按空对象处理）：{}", e.getMessage());
            return UnifiedModel.emptyObject();
        }
    }

    /**
     * MESSAGE 绑定解析（接口覆盖 → 应用默认 → 平台默认，D-M5-2 矩阵）：
     * 取链装配烘焙的 MESSAGE 实例（与请求方向实际执行同源，响应信封适配用同一 params）；
     * 命中 EnvelopeMessageAdapter → 返回其 params 用于响应信封适配；
     * 未命中（Noop 直通 / 无绑定）→ 返回 null，业务成败 = HTTP 状态。
     */
    private JsonNode envelopeParamsOf(InterfaceRow iface) {
        AdapterInstance inst = chainEngine.boundInstance(iface.id(), "MESSAGE");
        if (inst != null && "EnvelopeMessageAdapter".equals(inst.impl())) {
            return inst.params();
        }
        return null;
    }

    /** RESP 字段声明（D-M3-3 白名单过滤输入；空 = 不过滤） */
    private List<InterfaceRow.FieldDefRow> respDefs(InterfaceRow iface) {
        return interfaceRepository.findFieldDefs(iface.id()).stream()
                .filter(d -> "RESP".equals(d.kind()))
                .toList();
    }
}
