package com.deepx.apicenter.engine;

/**
 * 适配器三类（设计 §5.1）：鉴权 AUTH / 协议 PROTOCOL / 报文 MESSAGE。
 * 字段映射为链上固定步骤（接口级配置），不是适配器类型。
 */
public enum AdapterType {
    AUTH, PROTOCOL, MESSAGE
}
