package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.InterfaceDtos.BodyDto;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.FieldDefDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceResponse;
import com.deepx.apicenter.dto.InterfaceDtos.MappingDto;
import com.deepx.apicenter.dto.InterfaceDtos.ParamDto;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.GroupRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 接口管理：完整定义模型落库（主表 + 5 子表，单事务）。
 * 类型互斥校验矩阵（M1 设计 §2.5）+ 全量替换更新 + version 乐观锁（M1 评审确认点 5）。
 */
@Service
public class InterfaceService {

    private static final Set<String> IF_TYPES = Set.of("OUTBOUND", "INBOUND");
    private static final Set<String> METHODS = Set.of("POST", "GET", "PUT", "DELETE");
    private static final Set<String> PROTOCOLS = Set.of("JSON", "XML");
    private static final Set<String> OPS = Set.of("rename", "typeCast", "enumMap", "default", "condition", "aggregate");
    private static final Set<String> PARAM_OPS = Set.of("typeCast", "enumMap", "condition", "aggregate");
    private static final Set<String> ROLES = Set.of("MESSAGE", "AUTH", "CALLBACK_AUTH");

    private final InterfaceRepository interfaceRepository;
    private final AppRepository appRepository;
    private final GroupRepository groupRepository;

    public InterfaceService(InterfaceRepository interfaceRepository,
                            AppRepository appRepository,
                            GroupRepository groupRepository) {
        this.interfaceRepository = interfaceRepository;
        this.appRepository = appRepository;
        this.groupRepository = groupRepository;
    }

    // ---------- 查询 ----------

    public List<InterfaceResponse> list(String appId, Long groupId) {
        return interfaceRepository.findAll(appId, groupId).stream()
                .map(r -> toResponse(r, List.of(), List.of(), List.of(), List.of(), List.of()))
                .toList();
    }

    public InterfaceResponse detail(long id) {
        InterfaceRow row = interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        return toResponse(row,
                interfaceRepository.findParams(id),
                interfaceRepository.findBodies(id),
                interfaceRepository.findMappings(id),
                interfaceRepository.findFieldDefs(id),
                interfaceRepository.findBindings(id));
    }

    // ---------- 写入 ----------

    @Transactional
    public long create(InterfaceRequest req) {
        validate(req);
        if (interfaceRepository.existsByCode(req.code())) {
            throw BizException.fieldInvalid("接口标识已存在：" + req.code());
        }
        if (interfaceRepository.existsByPath(req.path())) {
            throw BizException.fieldInvalid("平台侧路径已存在：" + req.path());
        }
        validateBelong(req);
        long id = interfaceRepository.insertAndGetId(toRow(req, "DRAFT", 1));
        insertChildren(id, req);
        return id;
    }

    @Transactional
    public void update(long id, InterfaceRequest req) {
        InterfaceRow current = interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        validate(req);
        validateBelong(req);
        // 唯一性校验（排除自身）：避免撞 uk_interface_code / uk_interface_path 变成 500
        if (interfaceRepository.countByCode(req.code(), id) > 0) {
            throw BizException.fieldInvalid("接口标识已存在：" + req.code());
        }
        if (interfaceRepository.countByPath(req.path(), id) > 0) {
            throw BizException.fieldInvalid("平台侧路径已存在：" + req.path());
        }
        // 全量替换 + 乐观锁：version 不匹配 → 0 行 → 冲突（M1 评审确认点 5）
        int n = interfaceRepository.updateWithVersion(toRow(req, current.status(), req.version()));
        if (n == 0) {
            throw BizException.fieldInvalid("配置已被他人修改，请刷新后重试（乐观锁冲突）");
        }
        interfaceRepository.deleteChildren(id);
        insertChildren(id, req);
    }

    @Transactional
    public void publish(long id) {
        InterfaceRow row = interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        if (!List.of("DRAFT", "OFFLINE").contains(row.status())) {
            throw BizException.fieldInvalid("仅草稿/下线状态可发布，当前状态：" + row.status());
        }
        interfaceRepository.updateStatus(id, "PUBLISHED");
    }

    @Transactional
    public void offline(long id) {
        InterfaceRow row = interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        if (!"PUBLISHED".equals(row.status())) {
            throw BizException.fieldInvalid("仅已发布状态可下线，当前状态：" + row.status());
        }
        interfaceRepository.updateStatus(id, "OFFLINE");
    }

    @Transactional
    public void delete(long id) {
        interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        // 存在运行数据仅允许下线（M2 起校验 outbound_request / inbound_delivery）
        interfaceRepository.deleteCascade(id);
    }

    // ---------- 校验（M1 设计 §2.5 类型互斥矩阵） ----------

    private void validate(InterfaceRequest req) {
        if (!IF_TYPES.contains(req.ifType())) {
            throw BizException.fieldInvalid("非法接口类型：" + req.ifType() + "（OUTBOUND / INBOUND）");
        }
        if (!METHODS.contains(req.method())) {
            throw BizException.fieldInvalid("非法 HTTP 方法：" + req.method());
        }
        String pin = req.protocolIn() == null || req.protocolIn().isBlank() ? "JSON" : req.protocolIn();
        String pout = req.protocolOut() == null || req.protocolOut().isBlank() ? "JSON" : req.protocolOut();
        if (!PROTOCOLS.contains(pin) || !PROTOCOLS.contains(pout)) {
            throw BizException.fieldInvalid("协议仅支持 JSON / XML");
        }
        // ---- 类型互斥（OUTBOUND vs INBOUND） ----
        List<FieldDefDto> fieldDefs = req.fieldDefs() == null ? List.of() : req.fieldDefs();
        List<BindingDto> bindings = req.bindings() == null ? List.of() : req.bindings();
        List<ParamDto> params = req.params() == null ? List.of() : req.params();
        if ("OUTBOUND".equals(req.ifType())) {
            if (isBlank(req.upstreamPath())) {
                throw BizException.fieldInvalid("出站接口必填上游路径 upstreamPath");
            }
            if (!isBlank(req.callbackUrl())) {
                throw BizException.fieldInvalid("出站接口不允许配置回调地址 callbackUrl");
            }
            if (fieldDefs.stream().anyMatch(f -> "ACK".equals(f.kind()))) {
                throw BizException.fieldInvalid("出站接口不允许配置 ack 回执字段");
            }
            if (bindings.stream().anyMatch(b -> "CALLBACK_AUTH".equals(b.role()))) {
                throw BizException.fieldInvalid("出站接口不允许绑定回调验签（CALLBACK_AUTH）");
            }
        } else {
            if (isBlank(req.callbackUrl())) {
                throw BizException.fieldInvalid("入站接口必填回调地址 callbackUrl");
            }
            if (!isBlank(req.upstreamPath())) {
                throw BizException.fieldInvalid("入站接口不允许配置上游路径 upstreamPath");
            }
            if (fieldDefs.stream().anyMatch(f -> "RESP".equals(f.kind()))) {
                throw BizException.fieldInvalid("入站接口不允许配置出站响应字段");
            }
            if (bindings.stream().anyMatch(b -> "AUTH".equals(b.role()))) {
                throw BizException.fieldInvalid("入站接口不允许绑定供应商签名（AUTH）");
            }
            // 送达报文必填（设计 §3.1：入站回调的出站侧 = 送达报文）
            if (params.stream().noneMatch(p -> "OUT".equals(p.side()))) {
                throw BizException.fieldInvalid("入站接口必填出站侧（送达报文）参数");
            }
        }
        // ---- 绑定角色与参数侧值域 ----
        for (BindingDto b : bindings) {
            if (!ROLES.contains(b.role())) {
                throw BizException.fieldInvalid("非法绑定角色：" + b.role());
            }
        }
        for (ParamDto p : params) {
            if (!Set.of("IN", "OUT").contains(p.side())) {
                throw BizException.fieldInvalid("参数侧仅支持 IN / OUT：" + p.name());
            }
        }
        // ---- 字段映射校验（M0-02 §1） ----
        for (MappingDto m : req.mappings() == null ? List.<MappingDto>of() : req.mappings()) {
            if (isBlank(m.target())) {
                throw BizException.fieldInvalid("字段映射 target 必填");
            }
            if (!"default".equals(m.op()) && isBlank(m.source())) {
                throw BizException.fieldInvalid("字段映射 source 必填（仅 default 可空）");
            }
            if (!OPS.contains(m.op())) {
                throw BizException.fieldInvalid("非法映射操作：" + m.op());
            }
            if (PARAM_OPS.contains(m.op()) && isBlank(m.param())) {
                throw BizException.fieldInvalid("参数化操作 " + m.op() + " 需填操作参数 param");
            }
        }
    }

    /** 归属校验：应用存在；分组必须属于所选应用（两级下拉，M1 测试点） */
    private void validateBelong(InterfaceRequest req) {
        if (!appRepository.existsById(req.appId())) {
            throw BizException.appNotFound(req.appId());
        }
        groupRepository.findById(req.groupId()).ifPresentOrElse(
                g -> {
                    if (!g.appId().equals(req.appId())) {
                        throw BizException.fieldInvalid("分组不属于所选应用：" + g.name());
                    }
                },
                () -> {
                    throw BizException.fieldInvalid("分组不存在：" + req.groupId());
                });
    }

    // ---------- 私有 ----------

    private void insertChildren(long interfaceId, InterfaceRequest req) {
        List<ParamDto> params = req.params() == null ? List.of() : req.params();
        List<BodyDto> bodies = req.bodies() == null ? List.of() : req.bodies();
        List<MappingDto> mappings = req.mappings() == null ? List.of() : req.mappings();
        List<FieldDefDto> fieldDefs = req.fieldDefs() == null ? List.of() : req.fieldDefs();
        List<BindingDto> bindings = req.bindings() == null ? List.of() : req.bindings();
        interfaceRepository.insertParams(interfaceId, params.stream()
                .map(p -> new InterfaceRow.ParamRow(0, p.side(), p.name(), p.type() == null ? "string" : p.type(),
                        Boolean.TRUE.equals(p.required()), p.sample(), p.sortOrder() == null ? 0 : p.sortOrder()))
                .toList());
        interfaceRepository.insertBodies(interfaceId, bodies.stream()
                .map(b -> new InterfaceRow.BodyRow(0, b.side(), b.bodyType() == null ? "none" : b.bodyType(),
                        b.raw(), b.form()))
                .toList());
        interfaceRepository.insertMappings(interfaceId, mappings.stream()
                .map(m -> new InterfaceRow.MappingRow(0, m.source(), m.op(), m.target(), m.param(),
                        m.nullStrategy() == null ? "KEEP" : m.nullStrategy(),
                        m.sortOrder() == null ? 0 : m.sortOrder()))
                .toList());
        interfaceRepository.insertFieldDefs(interfaceId, fieldDefs.stream()
                .map(f -> new InterfaceRow.FieldDefRow(0, f.kind(), f.name(), f.type() == null ? "string" : f.type(),
                        f.desc(), f.sortOrder() == null ? 0 : f.sortOrder()))
                .toList());
        interfaceRepository.insertBindings(interfaceId, bindings.stream()
                .map(b -> new InterfaceRow.BindingRow(0, b.role(), b.adapterId(), b.version()))
                .toList());
    }

    private InterfaceRow toRow(InterfaceRequest req, String status, int version) {
        String pin = req.protocolIn() == null || req.protocolIn().isBlank() ? "JSON" : req.protocolIn();
        String pout = req.protocolOut() == null || req.protocolOut().isBlank() ? "JSON" : req.protocolOut();
        return new InterfaceRow(
                0, req.code(), req.name(), req.ifType(), req.method(), req.path(),
                pin, pout, req.appId(), req.groupId(),
                req.upstreamPath(), req.callbackUrl(), status, version,
                req.timeoutMs() == null ? 3000 : req.timeoutMs(),
                req.maxRetries() == null ? 4 : req.maxRetries(),
                req.desc(), null, null, null, null);
    }

    private InterfaceResponse toResponse(InterfaceRow row,
                                         List<InterfaceRow.ParamRow> params,
                                         List<InterfaceRow.BodyRow> bodies,
                                         List<InterfaceRow.MappingRow> mappings,
                                         List<InterfaceRow.FieldDefRow> fieldDefs,
                                         List<InterfaceRow.BindingRow> bindings) {
        return new InterfaceResponse(
                row.id(), row.code(), row.name(), row.ifType(), row.method(), row.path(),
                row.protocolIn(), row.protocolOut(), row.appId(), row.groupId(),
                row.upstreamPath(), row.callbackUrl(), row.status(), row.version(),
                row.timeoutMs(), row.maxRetries(), row.desc(),
                row.createdAt(), row.updatedAt(), row.appName(), row.groupName(),
                params, bodies, mappings, fieldDefs, bindings);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
