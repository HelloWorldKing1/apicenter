package com.deepx.apicenter.exception;

import com.deepx.apicenter.dto.ApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：所有异常转为统一信封 {code, msg, data}，HTTP 状态码 = 业务码 / 100。
 *
 * <p>2026-09-18 补（代码评审 P2）：原先只处理 BizException / 参数校验 / 兜底 Exception，
 * 导致「请求体畸形 JSON、路径拼错、方法不对」全部回 **500 平台内部错误**（误导调用方与前端）。
 * 现按类别给出 400 / 404 / 405 语义，业务码落在既有段位（400xx / 404xx / 405xx）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 40404 资源不存在（未匹配任何处理器，如 /api/admin/unknown）；40500 方法不支持 */
    public static final int RESOURCE_NOT_FOUND = 40404;
    public static final int METHOD_NOT_ALLOWED = 40500;

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> handleBiz(BizException e) {
        int http = e.getCode() / 100;
        if (http < 100 || http > 599) {
            http = 500;
        }
        return ResponseEntity.status(http).body(ApiResult.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(BizException.FIELD_INVALID, msg));
    }

    /** 请求体不可读（畸形 JSON / 类型不符 / Content-Type 不匹配）→ 400（原实现落入兜底 500） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(BizException.FIELD_INVALID, "请求体格式非法（JSON 语法或类型不匹配）"));
    }

    /** 路径/查询参数类型不符（如 /interfaces/abc）→ 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(BizException.FIELD_INVALID,
                        "参数类型非法：" + e.getName() + "=" + e.getValue()));
    }

    /** 未匹配的路径 → 404（注意：接入层通配路由会先生效，仅管理面/静态资源拼错走这里） */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.error(RESOURCE_NOT_FOUND, "请求的资源不存在：" + e.getResourcePath()));
    }

    /** 方法不支持 → 405（原先落 500） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResult.error(METHOD_NOT_ALLOWED, "请求方法不支持：" + e.getMethod()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleOther(Exception e) {
        log.error("平台内部错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(BizException.INTERNAL, "平台内部错误"));
    }
}
