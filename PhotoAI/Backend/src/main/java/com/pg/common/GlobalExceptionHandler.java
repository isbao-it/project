package com.pg.common;

import com.pg.entity.AjaxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.validation.BindException;

import javax.servlet.http.HttpServletRequest;

/** 将 MVC 和未捕获异常转换为统一信封，不把底层异常内容交给客户端。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 将业务拒绝转为统一信封，兼容旧控制器的错误工厂调用。
     * @param exception 仅含安全代码与状态的业务异常
     * @return 正确 HTTP 状态及三个信封字段
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<AjaxResult> businessError(BizException exception) {
        return ResponseEntity.status(exception.getHttpStatus()).body(AjaxResult.error(exception.getCode()));
    }

    /**
     * 处理参数绑定失败，避免把输入值和校验详情带回客户端。
     * @return 400 安全响应
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            ServletRequestBindingException.class, BindException.class})
    public ResponseEntity<AjaxResult> invalidRequest() {
        return ResponseEntity.badRequest().body(AjaxResult.error(ErrorCode.INVALID_REQUEST));
    }

    /** @return 405 响应；卡 01-T T3.2 固定使用 invalid_request。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<AjaxResult> methodNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(AjaxResult.error(ErrorCode.INVALID_REQUEST));
    }

    /** @return 未注册路由的 404 响应。 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<AjaxResult> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(AjaxResult.error(ErrorCode.NOT_FOUND));
    }

    /** @return 上传超限的 413 响应。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<AjaxResult> payloadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(AjaxResult.error(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    /**
     * 处理控制器主动拒绝的请求，只使用预定义提示。
     * @param exception 带 HTTP 状态的异常；原因文本不输出
     * @return HTTP 状态与错误代码匹配的响应
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<AjaxResult> rejected(ResponseStatusException exception) {
        for (ErrorCode code : ErrorCode.values()) {
            if (code.getStatus() == exception.getStatus().value()) {
                return ResponseEntity.status(exception.getStatus()).body(AjaxResult.error(code));
            }
        }
        return ResponseEntity.status(exception.getStatus()).body(AjaxResult.error(ErrorCode.INVALID_REQUEST));
    }

    /**
     * 记录未处理异常的位置并返回 500，日志也不携带可能含口令的异常消息。
     * @param exception 待处理异常
     * @param request 拦截器已写入身份的请求
     * @return 不含异常详情的 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AjaxResult> internalError(Exception exception, HttpServletRequest request) {
        // SQL/JWT 异常消息可能夹带敏感数据，只保留类型和栈位置，禁止复制消息和 cause。
        Exception safeException = new Exception(exception.getClass().getSimpleName());
        safeException.setStackTrace(exception.getStackTrace());
        log.error("请求处理失败 uid={}", request.getAttribute("uid"), safeException);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(AjaxResult.error(ErrorCode.INTERNAL_ERROR));
    }
}
