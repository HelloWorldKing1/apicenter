package com.deepx.apicenter.config;

/**
 * 配置变更事件（M5 D-M5-2 链缓存事件失效）：管理面配置变更发布，
 * ChainEngine 监听后失效链缓存——INTERFACE 精准移除单条、ADAPTER / APP 全清（影响面不定）。
 *
 * 发布点（漏一处即灰度不生效，D-M5-2 清单）：
 * InterfaceService（update / rollback / publish / offline / delete）→ INTERFACE
 * AdapterService（增改 / 启停 / 删）→ ADAPTER
 * AppService（默认三绑定变更 / 启停 / 删）→ APP
 * CredentialService 不进清单：出站凭证每请求实时读（findActive），轮换天然即时生效（二轮核实写明）。
 */
public record ConfigChangedEvent(Scope scope, Long interfaceId) {

    public enum Scope { INTERFACE, ADAPTER, APP }

    /** 接口配置变更：精准失效该接口的链缓存 */
    public static ConfigChangedEvent interfaceChanged(long interfaceId) {
        return new ConfigChangedEvent(Scope.INTERFACE, interfaceId);
    }

    /** 适配器定义变更（增改 / 启停 / 删）：影响面不定 → 全清 */
    public static ConfigChangedEvent adapterChanged() {
        return new ConfigChangedEvent(Scope.ADAPTER, null);
    }

    /** 应用变更（默认绑定 / 启停 / 删）：影响面不定 → 全清 */
    public static ConfigChangedEvent appChanged() {
        return new ConfigChangedEvent(Scope.APP, null);
    }
}
