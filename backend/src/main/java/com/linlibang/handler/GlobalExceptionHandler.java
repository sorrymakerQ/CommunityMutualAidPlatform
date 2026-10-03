package com.linlibang.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.linlibang.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一捕获异常并转换为标准 Result 响应，避免堆栈/内部信息泄露给前端
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 未登录或 Token 失效 */
    @ExceptionHandler(NotLoginException.class)
    public Result<?> handleNotLogin(NotLoginException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return Result.fail(401, "未登录或登录已过期");
    }

    /** 无所需角色 */
    @ExceptionHandler(NotRoleException.class)
    public Result<?> handleNotRole(NotRoleException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return Result.fail(403, "无权限访问");
    }

    /** 无所需权限 */
    @ExceptionHandler(NotPermissionException.class)
    public Result<?> handleNotPermission(NotPermissionException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return Result.fail(403, "无权限执行该操作");
    }

    /** 参数校验失败（@Valid 请求体对象） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.fail(400, msg.isEmpty() ? "参数校验失败" : msg);
    }

    /** 参数校验失败（表单绑定） */
    @ExceptionHandler(BindException.class)
    public Result<?> handleBind(BindException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(400, msg.isEmpty() ? "参数校验失败" : msg);
    }

    /** 参数校验失败（@Validated 路径/查询参数） */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<?> handleConstraint(ConstraintViolationException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        return Result.fail(400, e.getMessage());
    }

    /** 参数类型不匹配 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<?> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        return Result.fail(400, "参数类型错误: " + e.getName());
    }

    /** 请求体不可读（JSON 格式错误） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleNotReadable(HttpMessageNotReadableException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        return Result.fail(400, "请求体格式错误");
    }

    /** 唯一键冲突（如手机号重复注册） */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<?> handleDuplicateKey(DuplicateKeyException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        log.warn("唯一键冲突: {}", e.getMessage());
        return Result.fail(409, "数据已存在，请勿重复提交");
    }


    /** 上传文件过大 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<?> handleUploadSize(MaxUploadSizeExceededException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        return Result.fail(413, "上传文件过大");
    }

    /** 兜底：所有未预期的异常 */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        log.error("未预期异常", e);
        return Result.fail(500, "服务器开小差了，请稍后重试");
    }
}
