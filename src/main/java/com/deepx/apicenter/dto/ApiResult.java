package com.deepx.apicenter.dto;

/**
 * 统一响应信封（设计 §6.2）：{code, msg, data}，code=0 成功，非 0 失败。
 */
public record ApiResult<T>(int code, String msg, T data) {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(0, "ok", data);
    }

    public static ApiResult<Void> ok() {
        return new ApiResult<>(0, "ok", null);
    }

    public static ApiResult<Void> error(int code, String msg) {
        return new ApiResult<>(code, msg, null);
    }
}
