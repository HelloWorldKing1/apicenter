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
    /** 50000 平台内部错误 */
    public static final int INTERNAL = 50000;

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

    public static BizException appDisabled(String appId) {
        return new BizException(APP_DISABLED, "应用未启用：" + appId);
    }
}
