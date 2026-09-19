package com.deepx.apicenter.exception;

/**
 * 业务异常：携带统一错误码（设计 §6.2 错误码分段），由 GlobalExceptionHandler 转为统一信封。
 * 约定：HTTP 状态码 = 业务码 / 100（40001→400、40100→401、40402→404、42901→429、
 * 50201→502、50401→504、50000→500）。
 */
public class BizException extends RuntimeException {

    /** 40001 参数 / 校验失败 */
    public static final int FIELD_INVALID = 40001;
    /** 40102 应用未启用 */
    public static final int APP_DISABLED = 40102;
    /** 40401 接口不存在 */
    public static final int IFACE_NOT_FOUND = 40401;
    /** 40402 应用不存在 */
    public static final int APP_NOT_FOUND = 40402;
    /** 40403 接口配置快照不存在（M5 D-M5-1 回滚目标校验） */
    public static final int SNAPSHOT_NOT_FOUND = 40403;
    /** 50000 平台内部错误 */
    public static final int INTERNAL = 50000;

    // ---------- 管理面账号认证（2026-09-18；40100/40101 已被入站回调验签占用，故从 40104 起） ----------
    /** 40104 未登录 / 令牌缺失、无效或已过期（HTTP 401） */
    public static final int UNAUTHORIZED = 40104;
    /** 40105 用户名或密码错误 / 原密码不正确（HTTP 401） */
    public static final int BAD_CREDENTIALS = 40105;
    /** 40106 账号已锁定（连续失败达阈值，HTTP 401） */
    public static final int ACCOUNT_LOCKED = 40106;
    /** 40301 注册已关闭（HTTP 403） */
    public static final int REGISTER_DISABLED = 40301;
    /** 40901 用户名已存在（HTTP 409） */
    public static final int USERNAME_TAKEN = 40901;
    /** 40405 账号不存在（账号管理，HTTP 404） */
    public static final int ADMIN_USER_NOT_FOUND = 40405;
    /** 40302 只读角色（VIEWER）不能执行管理面写操作（HTTP 403） */
    public static final int READ_ONLY_ROLE = 40302;
    /** 40303 当前角色无账号管理权限（HTTP 403） */
    public static final int NO_ACCOUNT_ADMIN = 40303;

    private final int code;

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BizException fieldInvalid(String msg) {
        return new BizException(FIELD_INVALID, msg);
    }

    public static BizException appNotFound(String appId) {
        return new BizException(APP_NOT_FOUND, "应用不存在：" + appId);
    }

    public static BizException ifaceNotFound(long id) {
        return new BizException(IFACE_NOT_FOUND, "接口不存在：" + id);
    }

    /** M5 回滚目标快照不存在 */
    public static BizException snapshotNotFound(long id, java.math.BigDecimal version) {
        return new BizException(SNAPSHOT_NOT_FOUND, "接口快照不存在：接口 " + id + " v" + version);
    }

    public static BizException appDisabled(String appId) {
        return new BizException(APP_DISABLED, "应用未启用：" + appId);
    }
}
