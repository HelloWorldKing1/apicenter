package com.deepx.apicenter.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 适配器管理（M1 注册表骨架）：CRUD + params 按 impl 元数据 schema 校验。
 * 约束（M0-01 D6）：同一 impl 至多 1 条 enabled=1；凭证类参数不落 params（统一走应用凭证管理）。
 * 删除策略（schema.sql）：app 三列与 binding.adapter_id 引用置 NULL（回退「无鉴权 / 平台默认」）。
 */
@Service
public class AdapterService {

    private final AdapterRepository adapterRepository;
    private final AppRepository appRepository;
    private final InterfaceRepository interfaceRepository;
    private final AdapterImplCatalog catalog;
    private final ObjectMapper objectMapper;

    public AdapterService(AdapterRepository adapterRepository,
                          AppRepository appRepository,
                          InterfaceRepository interfaceRepository,
                          AdapterImplCatalog catalog,
                          ObjectMapper objectMapper) {
        this.adapterRepository = adapterRepository;
        this.appRepository = appRepository;
        this.interfaceRepository = interfaceRepository;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
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
    }

    @Transactional
    public void update(String id, AdapterRequest req) {
        adapterRepository.findById(id).orElseThrow(() -> BizException.fieldInvalid("适配器不存在：" + id));
        validate(req, id);
        adapterRepository.update(toRow(req));
    }

    @Transactional
    public void enable(String id, boolean enabled) {
        AdapterRow row = adapterRepository.findById(id).orElseThrow(() -> BizException.fieldInvalid("适配器不存在：" + id));
        if (enabled && adapterRepository.countEnabledByImpl(row.impl(), id) > 0) {
            throw BizException.fieldInvalid("同一实现类至多 1 条启用记录（M0-01 D6），请先停用同 impl 的其他适配器");
        }
        adapterRepository.updateEnabled(id, enabled);
    }

    @Transactional
    public void delete(String id) {
        adapterRepository.findById(id).orElseThrow(() -> BizException.fieldInvalid("适配器不存在：" + id));
        // 引用置 NULL：回退「无鉴权 / 平台默认」（schema.sql 删除策略）
        appRepository.clearAdapterRefs(id);
        interfaceRepository.clearBindingRefs(id);
        adapterRepository.delete(id);
    }

    // ---------- 私有 ----------

    private void validate(AdapterRequest req, String excludeId) {
        ImplMeta meta = catalog.byImpl(req.impl())
                .orElseThrow(() -> BizException.fieldInvalid("未知适配器实现类：" + req.impl()));
        if (!meta.type().equals(req.type())) {
            throw BizException.fieldInvalid("适配器类型不匹配：" + req.impl() + " 属于 " + meta.type());
        }
        if (excludeId == null && req.enabled() != null && req.enabled()
                && adapterRepository.countEnabledByImpl(req.impl(), "") > 0) {
            throw BizException.fieldInvalid("同一实现类至多 1 条启用记录（M0-01 D6）");
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
