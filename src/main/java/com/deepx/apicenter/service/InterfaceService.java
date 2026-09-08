package com.deepx.apicenter.service;

import com.deepx.apicenter.config.ConfigChangedEvent;
import com.deepx.apicenter.dto.InterfaceDtos.BodyDto;
import com.deepx.apicenter.dto.InterfaceDtos.CopyRequest;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.FieldDefDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceResponse;
import com.deepx.apicenter.dto.InterfaceDtos.MappingDto;
import com.deepx.apicenter.dto.InterfaceDtos.ParamDto;
import com.deepx.apicenter.dto.InterfaceDtos.RollbackRequest;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.GroupRepository;
import com.deepx.apicenter.repository.InboundDeliveryRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.repository.SnapshotRepository;
import java.math.BigDecimal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 接口管理：完整定义模型落库（主表 + 5 子表，单事务）。
 * 类型互斥校验矩阵（M1 设计 §2.5）+ 全量替换更新 + version 乐观锁（M1 评审确认点 5）。
 * M5 D-M5-1：版本快照——创建写 v1；每次成功配置更新 version+1 并写快照（change_note 取请求头）；
 * status 流转（发布 / 下线）不生成版本；回滚复用既有全量替换路径（版本号只增）。
 * M5 D-M5-2：配置变更发布 ConfigChangedEvent（INTERFACE）→ 链缓存事件失效。
 */
@Service
public class InterfaceService {

    /** 接口配置版本步进（v1.0 起，每次配置变更 / 回滚 +0.1） */
    private static final BigDecimal VERSION_STEP = new BigDecimal("0.1");
    private static final BigDecimal BASE_VERSION = new BigDecimal("1.0");

    private static final Set<String> IF_TYPES = Set.of("OUTBOUND", "INBOUND");
    private static final Set<String> METHODS = Set.of("POST", "GET", "PUT", "DELETE");
    private static final Set<String> PROTOCOLS = Set.of("JSON", "XML");
    private static final Set<String> OPS = Set.of("rename", "typeCast", "enumMap", "default", "condition", "aggregate");
    private static final Set<String> PARAM_OPS = Set.of("typeCast", "enumMap", "condition", "aggregate");
    private static final Set<String> ROLES = Set.of("MESSAGE", "AUTH", "CALLBACK_AUTH");

    private final InterfaceRepository interfaceRepository;
    private final AppRepository appRepository;
    private final GroupRepository groupRepository;
    private final OutboundRequestRepository outboundRequestRepository;
    private final InboundDeliveryRepository inboundDeliveryRepository;
    private final CallbackUrlValidator callbackUrlValidator;
    private final SnapshotRepository snapshotRepository;
    private final SnapshotSerializer snapshotSerializer;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;

    public InterfaceService(InterfaceRepository interfaceRepository,
                            AppRepository appRepository,
                            GroupRepository groupRepository,
                            OutboundRequestRepository outboundRequestRepository,
                            InboundDeliveryRepository inboundDeliveryRepository,
                            CallbackUrlValidator callbackUrlValidator,
                            SnapshotRepository snapshotRepository,
                            SnapshotSerializer snapshotSerializer,
                            ApplicationEventPublisher eventPublisher,
                            JdbcTemplate jdbcTemplate) {
        this.interfaceRepository = interfaceRepository;
        this.appRepository = appRepository;
        this.groupRepository = groupRepository;
        this.outboundRequestRepository = outboundRequestRepository;
        this.inboundDeliveryRepository = inboundDeliveryRepository;
        this.callbackUrlValidator = callbackUrlValidator;
        this.snapshotRepository = snapshotRepository;
        this.snapshotSerializer = snapshotSerializer;
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------- 查询 ----------

    public List<InterfaceResponse> list(String appId, Long groupId) {
        return list(appId, groupId, null, null, null);
    }

    public List<InterfaceResponse> list(String appId, Long groupId, String ifType, String status, String keyword) {
        return interfaceRepository.findAll(appId, groupId, ifType, status, keyword).stream()
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
        return createWithNote(req, null);
    }

    /** 创建（含快照 change_note，供 copy 标注来源；其余路径保持空说明） */
    private long createWithNote(InterfaceRequest req, String changeNote) {
        validate(req);
        if (interfaceRepository.existsByCode(req.code())) {
            throw BizException.fieldInvalid("接口标识已存在：" + req.code());
        }
        if (interfaceRepository.existsByPath(req.path())) {
            throw BizException.fieldInvalid("平台侧路径已存在：" + req.path());
        }
        validateBelong(req);
        long id = interfaceRepository.insertAndGetId(toRow(req, "DRAFT", BASE_VERSION, 0));
        insertChildren(id, req);
        // M5 D-M5-1：创建 → v1.0 首快照；P5 拍板：无 changeNote 时写「初始创建」（复制由 copy 传入来源文案）
        writeSnapshot(id, changeNote == null ? "初始创建" : changeNote, null);
        return id;
    }

    /**
     * 接口复制（方案 B，2026-09-07 拍板）：
     * 源 = detail 当前配置（六段子表全量）；新 code/path 必填；归属固定 = 源应用/源分组（不支持跨应用）；
     * name/desc/upstreamPath/callbackUrl 可空 = 沿用/默认；凭证随应用、快照历史与运行数据不复制；
     * 产物 DRAFT v1.0，首快照 change_note = 「复制自 {源code}#v{源版本}」。
     * 组装后走 createWithNote = 复用既有校验 / 唯一性 / 归属 / 级联插入（零旁路）。
     */
    @Transactional
    public long copy(long sourceId, CopyRequest req) {
        InterfaceResponse src = detail(sourceId);
        String name = isBlank(req.name()) ? src.name() + " 副本" : req.name().trim();
        String desc = isBlank(req.desc()) ? src.desc() : req.desc().trim();
        String upstream = isBlank(req.upstreamPath()) ? src.upstreamPath() : req.upstreamPath().trim();
        String callback = isBlank(req.callbackUrl()) ? src.callbackUrl() : req.callbackUrl().trim();
        InterfaceRequest target = new InterfaceRequest(
                req.code(), name, src.ifType(), src.method(), req.path(),
                src.protocolIn(), src.protocolOut(), src.appId(), src.groupId(),
                upstream, callback, null, src.timeoutMs(), src.maxRetries(), desc, BASE_VERSION,
                toParamDtos(src.params()), toBodyDtos(src.bodies()), toMappingDtos(src.mappings()),
                toFieldDefDtos(src.fieldDefs()), toBindingDtos(src.bindings()));
        return createWithNote(target, "复制自 " + src.code() + "#v" + src.version());
    }

    // ---------- 复制用：运行行模型 → 请求 DTO（仅固定字段搬移） ----------

    private List<ParamDto> toParamDtos(List<InterfaceRow.ParamRow> rows) {
        return rows.stream().map(r -> new ParamDto(r.side(), r.name(), r.type(),
                r.required(), r.sample(), r.sortOrder())).toList();
    }

    private List<BodyDto> toBodyDtos(List<InterfaceRow.BodyRow> rows) {
        return rows.stream().map(r -> new BodyDto(r.side(), r.bodyType(), r.raw(), r.form())).toList();
    }

    private List<MappingDto> toMappingDtos(List<InterfaceRow.MappingRow> rows) {
        return rows.stream().map(r -> new MappingDto(r.source(), r.op(), r.target(),
                r.param(), r.nullStrategy(), r.sortOrder())).toList();
    }

    private List<FieldDefDto> toFieldDefDtos(List<InterfaceRow.FieldDefRow> rows) {
        return rows.stream().map(r -> new FieldDefDto(r.kind(), r.name(), r.type(),
                r.desc(), r.sortOrder())).toList();
    }

    private List<BindingDto> toBindingDtos(List<InterfaceRow.BindingRow> rows) {
        return rows.stream().map(r -> new BindingDto(r.role(), r.adapterId(), r.version())).toList();
    }

    @Transactional
    public void update(long id, InterfaceRequest req) {
        update(id, req, null);
    }

    /**
     * 全量替换 + 版本化（M5）：成功更新后 version +0.1 并写新快照。
     * 变更说明完善（2026-09-07）：changeNote = X-Change-Note 手填**备注**（可空，≤250），
     * 落库 change_note = 自动 diff 摘要（+ 可选 ｜备注 段）、change_detail = 结构化 JSON。
     */
    @Transactional
    public void update(long id, InterfaceRequest req, String changeNote) {
        // H2 修复：备注超长直插抛 500 —— 唯一入口统一限长兜底
        if (changeNote != null && changeNote.length() > 250) {
            throw BizException.fieldInvalid("变更备注超长（最多 250 字）");
        }
        InterfaceRow current = interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        // 旧配置 = 上一个快照（每次成功配置变更均写快照，与当前库态一致、零额外查询）
        String note;
        String detail;
        // 旧配置优先取上一个快照；对无快照的历史行（M5 前遗留/数据被清）回退为从当前库态现场构建，
        // 保证任何成功更新都生成变更摘要（2026-09-08 修复：prev 缺失曾导致 change_note 为 null）
        SnapshotRepository.SnapshotDetail prev = snapshotRepository.find(id, current.version()).orElse(null);
        String oldJson = prev != null ? prev.configJson() : currentConfigJson(id);
        if (oldJson != null) {
            SnapshotChangeDiff.DiffResult diff =
                    SnapshotChangeDiff.build(oldJson, newSnapshotJson(req), changeNote);
            note = diff.summary();
            detail = diff.detailJson();
        } else {
            note = changeNote == null || changeNote.isBlank() ? null : changeNote.trim();
            detail = null;
        }
        updatePersist(id, req, note, detail);
    }

    /** 现场构建当前配置 JSON（无快照时的 diff 旧配置来源；与 writeSnapshot 读取同源） */
    private String currentConfigJson(long id) {
        InterfaceRow row = interfaceRepository.findById(id)
                .orElseThrow(() -> BizException.ifaceNotFound(id));
        return snapshotSerializer.toJson(row,
                interfaceRepository.findParams(id),
                interfaceRepository.findBodies(id),
                interfaceRepository.findMappings(id),
                interfaceRepository.findFieldDefs(id),
                interfaceRepository.findBindings(id));
    }

    /** 更新持久化（校验 / 唯一性 / 乐观锁 / 全量替换 / 写快照 + 事件）；说明由调用方给定。
     * 注意：私有方法不挂事务（@Transactional 对私有无效），须在调用方事务内（update/rollback 已 @Transactional）。 */
    private void updatePersist(long id, InterfaceRequest req, String changeNote, String changeDetail) {
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
        int n = interfaceRepository.updateWithVersion(toRow(req, current.status(), req.version(), id));
        if (n == 0) {
            throw BizException.fieldInvalid("配置已被他人修改，请刷新后重试（乐观锁冲突）");
        }
        interfaceRepository.deleteChildren(id);
        insertChildren(id, req);
        writeSnapshot(id, changeNote, changeDetail); // 新版本 = req.version + 0.1（乐观锁自增后回读）
        eventPublisher.publishEvent(ConfigChangedEvent.interfaceChanged(id));
    }

    /**
     * 回滚到指定版本（M5 D-M5-1）：以快照内容走既有全量替换路径（不旁路校验），
     * currentVersion 乐观锁（并发更新冲突 → 40001）；版本号历史只增（update 内 version+1）；
     * status 保持当前生命周期状态（快照不含 status）；回滚即配置变更 → 发布 INTERFACE 事件。
     */
    @Transactional
    public void rollback(long id, RollbackRequest req) {
        interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        SnapshotRepository.SnapshotDetail snap = snapshotRepository.find(id, req.targetVersion())
                .orElseThrow(() -> BizException.snapshotNotFound(id, req.targetVersion()));
        InterfaceRequest request = snapshotSerializer.toRequest(snap.configJson(), req.currentVersion());
        // 评审定稿：回滚说明极简 = 「回滚至 v{目标}」（不携带 operator/reason、不做 from 补全），detail 为空
        updatePersist(id, request, "回滚至 v" + req.targetVersion(), null);
    }

    // ---------- 版本查询（M5 D-M5-1） ----------

    /** 版本列表（倒序分页；不含 config_json——详情单独查） */
    public VersionPage versions(long id, int page, int pageSize) {
        interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        long total = snapshotRepository.count(id);
        int offset = Math.max(0, (page - 1) * pageSize);
        List<VersionItem> items = snapshotRepository.listPage(id, offset, pageSize).stream()
                .map(s -> new VersionItem(s.version(), s.changeNote(), s.createdAt(), s.hasDetail()))
                .toList();
        return new VersionPage(items, total, page, pageSize);
    }

    /** 快照详情（版本历史「查看快照 JSON」） */
    public VersionDetail versionDetail(long id, BigDecimal version) {
        interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        SnapshotRepository.SnapshotDetail snap = snapshotRepository.find(id, version)
                .orElseThrow(() -> BizException.snapshotNotFound(id, version));
        return new VersionDetail(snap.version(), snap.changeNote(), snap.createdAt(),
                snap.configJson(), snap.changeDetail());
    }

    public record VersionItem(BigDecimal version, String changeNote, LocalDateTime createdAt, boolean hasDetail) {
    }

    public record VersionPage(List<VersionItem> list, long total, int page, int pageSize) {
    }

    public record VersionDetail(BigDecimal version, String changeNote, LocalDateTime createdAt,
                                String configJson, String changeDetail) {
    }

    @Transactional
    public void publish(long id) {
        InterfaceRow row = interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        if (!List.of("DRAFT", "OFFLINE").contains(row.status())) {
            throw BizException.fieldInvalid("仅草稿/下线状态可发布，当前状态：" + row.status());
        }
        interfaceRepository.updateStatus(id, "PUBLISHED");
        eventPublisher.publishEvent(ConfigChangedEvent.interfaceChanged(id));
    }

    @Transactional
    public void offline(long id) {
        InterfaceRow row = interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        if (!"PUBLISHED".equals(row.status())) {
            throw BizException.fieldInvalid("仅已发布状态可下线，当前状态：" + row.status());
        }
        interfaceRepository.updateStatus(id, "OFFLINE");
        eventPublisher.publishEvent(ConfigChangedEvent.interfaceChanged(id));
    }

    @Transactional
    public void delete(long id) {
        interfaceRepository.findById(id).orElseThrow(() -> BizException.ifaceNotFound(id));
        // 删除守卫（schema.sql 删除策略）：存在运行数据仅允许下线，
        // 否则 outbound_request / inbound_delivery 悬空（无外键不报错，但监控/重放/对账全部失效）。
        // M3 补查 inbound_delivery（M2 仅守卫 outbound_request，评审 N4 遗留）
        if (outboundRequestRepository.countByInterface(id) > 0
                || inboundDeliveryRepository.countByInterface(id) > 0) {
            throw BizException.fieldInvalid("接口存在运行数据，仅允许下线（禁止删除）");
        }
        // 调用日志保留、引用置 NULL（schema.sql 约定：可观测数据不丢）
        jdbcTemplate.update("UPDATE call_log SET interface_id = NULL WHERE interface_id = ?", id);
        interfaceRepository.deleteCascade(id);
        eventPublisher.publishEvent(ConfigChangedEvent.interfaceChanged(id));
    }

    // ---------- 私有 ----------

    /** 写快照：回读当前状态（版本已自增、子表已重建）序列化为 config_json；changeDetail 可空 */
    private void writeSnapshot(long interfaceId, String changeNote, String changeDetail) {
        InterfaceRow row = interfaceRepository.findById(interfaceId)
                .orElseThrow(() -> BizException.ifaceNotFound(interfaceId));
        snapshotRepository.insert(interfaceId, row.version(),
                snapshotSerializer.toJson(row,
                        interfaceRepository.findParams(interfaceId),
                        interfaceRepository.findBodies(interfaceId),
                        interfaceRepository.findMappings(interfaceId),
                        interfaceRepository.findFieldDefs(interfaceId),
                        interfaceRepository.findBindings(interfaceId)),
                changeNote, changeDetail);
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
            // 上游路径应为相对路径（拼应用服务地址），拒绝绝对 URL 与路径穿越（中危 #7）
            if (req.upstreamPath().matches("^https?://.*") || req.upstreamPath().contains("..")) {
                throw BizException.fieldInvalid("上游路径应为相对路径（拼应用服务地址），不含协议与「..」");
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
            // 回调地址必须是完整 URL（http/https），防错配与 SSRF 面（中危 #7）
            if (!req.callbackUrl().matches("^https?://[^\\s]+$")) {
                throw BizException.fieldInvalid("回调地址必须是完整 URL（http/https）");
            }
            // SSRF 防护（M0-03 §4 点名 M3）：内网 / 回环地址拒绝（callback-allow-private 开关控制）
            callbackUrlValidator.validateForSave(req.callbackUrl());
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

    /** 变更说明 diff 用：把本次请求归一化为“新快照 JSON”（与落库序列化同源，子表映射同 insertChildren） */
    private String newSnapshotJson(InterfaceRequest req) {
        InterfaceRow row = toRow(req, "DRAFT", BASE_VERSION, 0);
        return snapshotSerializer.toJson(row,
                paramRows(req), bodyRows(req), mappingRows(req), fieldDefRows(req), bindingRows(req));
    }

    // ---------- 私有 ----------

    private void insertChildren(long interfaceId, InterfaceRequest req) {
        List<ParamDto> params = req.params() == null ? List.of() : req.params();
        List<BodyDto> bodies = req.bodies() == null ? List.of() : req.bodies();
        List<MappingDto> mappings = req.mappings() == null ? List.of() : req.mappings();
        List<FieldDefDto> fieldDefs = req.fieldDefs() == null ? List.of() : req.fieldDefs();
        List<BindingDto> bindings = req.bindings() == null ? List.of() : req.bindings();
        interfaceRepository.insertParams(interfaceId, toParamRows(params));
        interfaceRepository.insertBodies(interfaceId, toBodyRows(bodies));
        interfaceRepository.insertMappings(interfaceId, toMappingRows(mappings));
        interfaceRepository.insertFieldDefs(interfaceId, toFieldDefRows(fieldDefs));
        interfaceRepository.insertBindings(interfaceId, toBindingRows(bindings));
    }

    /** 新快照 diff 用：请求子表 → 行模型（默认值规则与 insertChildren 完全一致，防 diff 误报） */
    private List<InterfaceRow.ParamRow> paramRows(InterfaceRequest req) {
        return toParamRows(req.params() == null ? List.of() : req.params());
    }

    private List<InterfaceRow.BodyRow> bodyRows(InterfaceRequest req) {
        return toBodyRows(req.bodies() == null ? List.of() : req.bodies());
    }

    private List<InterfaceRow.MappingRow> mappingRows(InterfaceRequest req) {
        return toMappingRows(req.mappings() == null ? List.of() : req.mappings());
    }

    private List<InterfaceRow.FieldDefRow> fieldDefRows(InterfaceRequest req) {
        return toFieldDefRows(req.fieldDefs() == null ? List.of() : req.fieldDefs());
    }

    private List<InterfaceRow.BindingRow> bindingRows(InterfaceRequest req) {
        return toBindingRows(req.bindings() == null ? List.of() : req.bindings());
    }

    private List<InterfaceRow.ParamRow> toParamRows(List<ParamDto> params) {
        return params.stream()
                .map(p -> new InterfaceRow.ParamRow(0, p.side(), p.name(), p.type() == null ? "string" : p.type(),
                        Boolean.TRUE.equals(p.required()), p.sample(), p.sortOrder() == null ? 0 : p.sortOrder()))
                .toList();
    }

    private List<InterfaceRow.BodyRow> toBodyRows(List<BodyDto> bodies) {
        return bodies.stream()
                .map(b -> new InterfaceRow.BodyRow(0, b.side(), b.bodyType() == null ? "none" : b.bodyType(),
                        b.raw(), b.form()))
                .toList();
    }

    private List<InterfaceRow.MappingRow> toMappingRows(List<MappingDto> mappings) {
        return mappings.stream()
                .map(m -> new InterfaceRow.MappingRow(0, m.source(), m.op(), m.target(), m.param(),
                        m.nullStrategy() == null ? "KEEP" : m.nullStrategy(),
                        m.sortOrder() == null ? 0 : m.sortOrder()))
                .toList();
    }

    private List<InterfaceRow.FieldDefRow> toFieldDefRows(List<FieldDefDto> fieldDefs) {
        return fieldDefs.stream()
                .map(f -> new InterfaceRow.FieldDefRow(0, f.kind(), f.name(), f.type() == null ? "string" : f.type(),
                        f.desc(), f.sortOrder() == null ? 0 : f.sortOrder()))
                .toList();
    }

    private List<InterfaceRow.BindingRow> toBindingRows(List<BindingDto> bindings) {
        return bindings.stream()
                .map(b -> new InterfaceRow.BindingRow(0, b.role(), b.adapterId(), b.version()))
                .toList();
    }

    private InterfaceRow toRow(InterfaceRequest req, String status, BigDecimal version, long id) {
        String pin = req.protocolIn() == null || req.protocolIn().isBlank() ? "JSON" : req.protocolIn();
        String pout = req.protocolOut() == null || req.protocolOut().isBlank() ? "JSON" : req.protocolOut();
        return new InterfaceRow(
                id, req.code(), req.name(), req.ifType(), req.method(), req.path(),
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
