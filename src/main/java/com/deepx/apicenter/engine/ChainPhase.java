package com.deepx.apicenter.engine;

/**
 * 适配器链六阶段（M0-01 §4 固定链顺序）：
 * 入站鉴权 → 协议解码 → 报文适配 → 字段映射 → 协议编码 → 出站鉴权。
 */
public enum ChainPhase {
    INBOUND_AUTH("入站鉴权"),
    DECODE("协议解码"),
    MESSAGE("报文适配"),
    MAPPING("字段映射"),
    ENCODE("协议编码"),
    OUTBOUND_AUTH("出站鉴权");

    private final String label;

    ChainPhase(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
