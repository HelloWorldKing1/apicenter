package com.deepx.apicenter.service;

import com.deepx.apicenter.config.ConfigChangedEvent;
import com.deepx.apicenter.dto.AdapterDtos.AdapterRequest;
import com.deepx.apicenter.dto.AdapterDtos.AdapterResponse;
import com.deepx.apicenter.dto.AdapterDtos.ImplField;
import com.deepx.apicenter.dto.AdapterDtos.ImplMeta;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdapterRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 适配器管理（M1 注册表骨架）：CRUD + params 按 impl 元数据 schema 校验。
 * 约束（D6' 定稿，2026-09-08 弃用灰度版本路由）：
 * - adapter.name 全表唯一（id 仍是唯一键与绑定键，name 防重名）；
 * - 同 (impl, version) 允许多条启用并存——不再按 (impl, version) 拦启用；
 *   多实例并行首选同 impl 不同 version（灰度/并存正规形态），同 version 多实例靠 name 区分；
 * - binding.version 不再参与运行时路由（D1：绑定即实例，恒用绑定行 adapter_id），仅记录/留痕。
 * 凭证类参数不落 params（统一走应用凭证管理）。变更发布 ADAPTER 事件 → 链缓存全清（D-M5-2）。
 * 删除策略（schema.sql）：app 三列与 binding.adapter_id 引用置 NULL（回退「无鉴权 / 平台默认」）。
 */
@Service
public class AdapterService {

    private final AdapterRepository adapterRepository;
    private final AppRepository appRepository;
    private final InterfaceRepository interfaceRepository;
    private final AdapterImplCatalog catalog;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public AdapterService(AdapterRepository adapterRepository,
                          AppRepository appRepository,
                          InterfaceRepository interfaceRepository,
                          AdapterImplCatalog catalog,
                          ObjectMapper objectMapper,
                          ApplicationEventPublisher eventPublisher) {
        this.adapterRepository = adapterRepository;
        this.appRepository = appRepository;
        this.interfaceRepository = interfaceRepository;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    public List<AdapterResponse> list(String type) {
        return adapterRepository.findAll(type).stream().map(AdapterResponse::from).toList();
    }

    /** impl 元数据清单（管理面动态渲染参数表单） */
    public List<ImplMeta> implCatalog() {
        return catalog.all();
    }

    @Transactional
    public void create(AdapterRequest req) {
        if (req.id() == null || req.id().isBlank()) {
            throw BizException.fieldInvalid("适配器标识不能为空");
        }
        if (adapterRepository.existsById(req.id())) {
            throw BizException.fieldInvalid("适配器标识已存在：" + req.id());
        }
        validate(req, null);
        adapterRepository.insert(toRow(req));
        eventPublisher.publishEvent(ConfigChangedEvent.adapterChanged());
    }

    @Transactional
    public void update(String id, AdapterRequest req) {
        adapterRepository.findById(id).orElseThrow(() -> BizException.fieldInvalid("适配器不存在：" + id));
        validate(req, id);
        adapterRepository.update(toRow(req));
        eventPublisher.publishEvent(ConfigChangedEvent.adapterChanged());
    }

    @Transactional
    public void enable(String id, boolean enabled) {
        adapterRepository.findById(id).orElseThrow(() -> BizException.fieldInvalid("适配器不存在：" + id));
        // D6'：启用不再受同 (impl, version) 限制（同 impl+version 允许多启用并存，靠 name 区分）
        adapterRepository.updateEnabled(id, enabled);
        eventPublisher.publishEvent(ConfigChangedEvent.adapterChanged());
    }

    @Transactional
    public void delete(String id) {
        adapterRepository.findById(id).orElseThrow(() -> BizException.fieldInvalid("适配器不存在：" + id));
        // 引用置 NULL：回退「无鉴权 / 平台默认」（schema.sql 删除策略）
        appRepository.clearAdapterRefs(id);
        interfaceRepository.clearBindingRefs(id);
        adapterRepository.delete(id);
        eventPublisher.publishEvent(ConfigChangedEvent.adapterChanged());
    }

    // ---------- 私有 ----------

    private void validate(AdapterRequest req, String excludeId) {
        ImplMeta meta = catalog.byImpl(req.impl())
                .orElseThrow(() -> BizException.fieldInvalid("未知适配器实现类：" + req.impl()));
        if (!meta.type().equals(req.type())) {
            throw BizException.fieldInvalid("适配器类型不匹配：" + req.impl() + " 属于 " + meta.type());
        }
        // D6'：name 全表唯一（create/update 均校验，排除自身）；同 (impl, version) 允许多启用，不再拦
        if (req.name() != null && adapterRepository.countByName(req.name(), excludeId == null ? "" : excludeId) > 0) {
            throw BizException.fieldInvalid("适配器名称已存在（名称全表唯一）：" + req.name());
        }
        // params 按 impl schema 校验并归一化
        JsonNode node;
        try {
            node = (req.params() == null || req.params().isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(req.params());
        } catch (Exception e) {
            throw BizException.fieldInvalid("适配器参数不是合法 JSON");
        }
        if (!node.isObject()) {
            throw BizException.fieldInvalid("适配器参数必须是 JSON 对象");
        }
        for (ImplField f : meta.fields()) {
            JsonNode v = node.get(f.key());
            boolean blank = v == null || v.isNull() || v.asText().isBlank();
            if (f.required() && blank) {
                throw BizException.fieldInvalid("缺少必填参数：" + f.label());
            }
            if ("secret".equals(f.kind()) && !blank) {
                throw BizException.fieldInvalid("凭证类参数不落适配器配置，请在应用凭证管理中配置：" + f.label());
            }
            if (f.options() != null && !f.options().isEmpty() && !blank
                    && !f.options().contains(v.asText())) {
                throw BizException.fieldInvalid("参数取值非法：" + f.label() + "（可选：" + f.options() + "）");
            }
        }
    }

    private AdapterRow toRow(AdapterRequest req) {
        return new AdapterRow(
                req.id(), req.name(), req.type(), req.impl(),
                req.enabled() == null || req.enabled(),
                req.version() == null || req.version().isBlank() ? "1.0" : req.version(),
                req.params(), null, null);
    }
}
