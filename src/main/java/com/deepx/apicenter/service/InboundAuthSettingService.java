package com.deepx.apicenter.service;

import com.deepx.apicenter.config.InboundAuthSettingChangedEvent;
import com.deepx.apicenter.dto.InboundAuthSettingDtos.SettingView;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdapterRow;
import com.deepx.apicenter.model.InboundAuthSettingRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.InboundAuthSettingRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 入站鉴权平台设置（**单行表**，2026-09-24 v1.2）—— 读（带缓存）+ 写（校验 + 留痕 + 事件失效）。
 *
 * <p><b>为什么必须便宜</b>：闸门每请求都要问「平台默认方式是哪个 / 是否强制自报主体」，
 * 不能每请求打一次库 ⇒ {@link #current()} 走**内存缓存**（`volatile` + 60s TTL 兜底）。
 *
 * <p><b>三级取值里的位置</b>：接口绑定 `CLIENT_AUTH` → **本表 `default_adapter_id`** → 都没有 ⇒
 * fail-closed `40108`（**绝不回退 Noop**，设计方案 v1.2 §6.5）。
 *
 * <p><b>写入纪律（三条，缺一条就是运维事故）</b>：
 * <ol>
 *   <li>引用校验：指向的适配器必须**存在、类型为 auth、且启用**（否则保存成功但运行期一律 40108）；</li>
 *   <li>留痕：`updated_by` / `updated_at` + 结构化 `log.warn`，**放松类**变更额外落
 *       `alert_event(metric=inbound_auth_setting_changed, level=WARN)`；</li>
 *   <li>失效：改写缓存并发布 {@link InboundAuthSettingChangedEvent}（与 60s TTL 双保险）。
 * </ol>
 */
@Service
public class InboundAuthSettingService {

    /** 多实例兜底：其他实例最长 60s 内看到新值（与链缓存 TTL 兜底同思路；单实例下事件即时失效） */
    private static final long TTL_MILLIS = 60_000L;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final InboundAuthSettingRepository settingRepository;
    private final AdapterRepository adapterRepository;
    private final ApplicationEventPublisher events;
    private final AlertService alertService;

    /** 缓存（可空对象模式：null 值也缓存，避免「未配置」时每请求打库） */
    private volatile Cache cache;

    public InboundAuthSettingService(InboundAuthSettingRepository settingRepository,
                                     AdapterRepository adapterRepository,
                                     ApplicationEventPublisher events,
                                     AlertService alertService) {
        this.settingRepository = settingRepository;
        this.adapterRepository = adapterRepository;
        this.events = events;
        this.alertService = alertService;
    }

    /**
     * 当前设置（**闸门热路径**）：缓存命中且未过 TTL 即返回；否则读库并缓存。
     * 行缺失（老库未初始化）时返回「未配置」语义：方式为空、强制自报主体 = true（安全默认）。
     */
    public InboundAuthSettingRow current() {
        Cache c = cache;
        if (c != null && !c.expired()) {
            return c.row();
        }
        InboundAuthSettingRow row = settingRepository.find().orElse(defaultRow());
        cache = new Cache(row, System.currentTimeMillis());
        return row;
    }

    /** 平台默认鉴权方式 id（可空 = 未配置） */
    public String defaultAdapterId() {
        return current().defaultAdapterId();
    }

    /** 是否强制自报主体（默认 true = v1.1 兼容档） */
    public boolean requireClientId() {
        return current().requireClientId();
    }

    /** 管理面读（含适配器名称，便于页面展示「方式」而不只是 id） */
    public SettingView view() {
        InboundAuthSettingRow row = current();
        return new SettingView(row.defaultAdapterId(), adapterName(row.defaultAdapterId()),
                row.requireClientId(), row.updatedBy(),
                row.updatedAt() == null ? null : TIME_FMT.format(row.updatedAt()));
    }

    /**
     * 保存设置（页面可改、改后即时生效）。
     *
     * <p>⚠️ 不做「静默兜底」：适配器引用不存在 / 类型不对 / 已停用 ⇒ `40001`（与项目「配置非法一律拒绝」一致）。
     */
    @Transactional
    public SettingView save(String defaultAdapterId, boolean requireClientId, String operator) {
        String normalized = normalizeAdapterId(defaultAdapterId);
        validateAdapterRef(normalized);

        InboundAuthSettingRow before = current();
        boolean relaxation = isRelaxation(before, normalized, requireClientId);

        settingRepository.ensureRow();   // 老库未初始化时兜底（幂等）
        settingRepository.save(normalized, requireClientId, operator);
        invalidate();

        String change = describe(before, normalized, requireClientId);
        if (relaxation) {
            // 放松类变更必须留痕且有告警：平台默认影响所有未绑定接口，一个误点就是全局放宽
            alertService.recordInboundAuthSettingChanged(change, operator);
        } else {
            log().info("入站鉴权平台设置已更新（{}，操作者 {}）", change, operator);
        }
        events.publishEvent(new InboundAuthSettingChangedEvent(normalized, requireClientId, operator, relaxation));
        return view();
    }

    /** 影响面预览：平台默认变更会影响多少个接口（未绑定 `CLIENT_AUTH` 的出站中转接口） */
    public int affectedInterfaceCount() {
        return settingRepository.countInterfacesWithoutClientAuthBinding();
    }

    /** 事件监听：本 Bean 自己订阅（保住「新增配置入口必须补发事件」的统一落点） */
    @EventListener
    public void onChanged(InboundAuthSettingChangedEvent evt) {
        InboundAuthSettingRow fresh = settingRepository.find().orElse(defaultRow());
        cache = new Cache(fresh, System.currentTimeMillis());
    }

    /** 供测试与「适配器变更」联动调用：丢掉缓存，下次读重新打库 */
    public void invalidate() {
        cache = null;
    }

    // ---------- 私有 ----------

    /** 空串归一为 null（「未配置」只有一种表示） */
    private String normalizeAdapterId(String defaultAdapterId) {
        return defaultAdapterId == null || defaultAdapterId.isBlank() ? null : defaultAdapterId.trim();
    }

    /** 引用完整性（应用层保证，无外键）+ 语义校验：必须是 auth 类型且启用的适配器 */
    private void validateAdapterRef(String adapterId) {
        if (adapterId == null) {
            return;
        }
        AdapterRow adapter = adapterRepository.findById(adapterId)
                .orElseThrow(() -> BizException.fieldInvalid("鉴权适配器不存在：" + adapterId));
        if (!"auth".equals(adapter.type())) {
            throw BizException.fieldInvalid("平台默认入站鉴权方式必须是鉴权类适配器（type=auth）：" + adapterId);
        }
        if (!adapter.enabled()) {
            throw BizException.fieldInvalid("鉴权适配器已停用，作为平台默认会导致接口一律拒绝（40108）：" + adapterId);
        }
    }

    /**
     * 是否「放松类」变更：`require_client_id` 由 1 变 0（主体不再强制），
     * 或**鉴权方式发生变化**（宽严无法自动判定，故一律按放松留痕 —— 宁可多一条告警，不可漏一次放宽）。
     */
    private boolean isRelaxation(InboundAuthSettingRow before, String newAdapterId, boolean newRequireClientId) {
        if (before.requireClientId() && !newRequireClientId) {
            return true;
        }
        return !java.util.Objects.equals(before.defaultAdapterId(), newAdapterId);
    }

    private String describe(InboundAuthSettingRow before, String newAdapterId, boolean newRequireClientId) {
        return "平台默认方式 " + label(before.defaultAdapterId()) + " → " + label(newAdapterId)
                + "；强制自报主体 " + before.requireClientId() + " → " + newRequireClientId;
    }

    private String label(String adapterId) {
        return adapterId == null ? "（未配置）" : adapterId;
    }

    private String adapterName(String adapterId) {
        if (adapterId == null) {
            return null;
        }
        return adapterRepository.findById(adapterId).map(AdapterRow::name).orElse(null);
    }

    private InboundAuthSettingRow defaultRow() {
        // 安全默认：方式未配置（fail-closed）+ 强制自报主体（v1.1 行为）
        return new InboundAuthSettingRow(null, true, null, null);
    }

    private static org.slf4j.Logger log() {
        return org.slf4j.LoggerFactory.getLogger(InboundAuthSettingService.class);
    }

    /** 带加载时间的缓存条目 */
    private record Cache(InboundAuthSettingRow row, long loadedAt) {
        boolean expired() {
            return System.currentTimeMillis() - loadedAt > TTL_MILLIS;
        }
    }
}
