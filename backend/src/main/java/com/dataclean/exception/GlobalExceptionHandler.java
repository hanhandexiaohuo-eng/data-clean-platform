package com.dataclean.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>约束：
 * <ul>
 *   <li>响应中不得出现 SQL、堆栈、文件路径或配置项；</li>
 *   <li>参数类错误统一 400，认证 401，权限 403，资源 404，冲突 409，限流 429，服务不可用 503；</li>
 *   <li>服务端日志同样不打印密钥、Authorization 头或完整敏感数据。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}", e.getCode());
        HttpStatus status = switch (e.getCode()) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthenticationException(AuthenticationException e) {
        log.warn("认证失败: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail(401, "未授权，请先登录"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(403, "权限不足"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败");
        return ResponseEntity.badRequest().body(Result.fail(400, "参数校验失败: " + message));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        log.warn("请求参数不合法: {}", e.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(Result.fail(400, "请求参数格式不正确"));
    }

    /** 请求体不是合法 JSON：客户端错误，返回 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体格式不正确");
        return ResponseEntity.badRequest().body(Result.fail(400, "请求体格式不正确"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("文件上传超限");
        return ResponseEntity.badRequest().body(Result.fail(400, "文件大小超过限制，最大支持 100MB"));
    }

    /** 不存在的接口/静态资源：404，而不是落到兜底 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("接口不存在");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail(404, "接口不存在"));
    }

    /** 数据库异常：响应通用错误，日志只记录异常类型，避免 SQL 与参数外泄 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> handleDataAccess(DataAccessException e) {
        log.error("数据库操作失败: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(500, "数据处理失败，请稍后重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(500, "服务器内部错误"));
    }
}
