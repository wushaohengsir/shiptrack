package com.shiptrack.api.error;

import com.shiptrack.contract.dto.ApiError;
import com.shiptrack.contract.dto.ErrorResponse;
import com.shiptrack.contract.enums.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Maps every failure to the §1.4 error envelope.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException error, HttpServletRequest request) {
        return build(error.code(), error.getMessage(), error.details(), request);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ErrorResponse> handleValidation(Exception error, HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, "请求参数不合法", null, request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateKeyException error,
                                                         HttpServletRequest request) {
        return build(ErrorCode.CONFLICT, "唯一约束冲突", null, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException error,
                                                        HttpServletRequest request) {
        return build(ErrorCode.NOT_FOUND, "资源不存在", null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception error, HttpServletRequest request) {
        log.error("unhandled request failure", error);
        return build(ErrorCode.INTERNAL_ERROR, "服务内部错误", null, request);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message,
                                                Map<String, Object> details,
                                                HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        ApiError body = new ApiError(
                code,
                message,
                requestId == null ? null : requestId.toString(),
                details);
        return ResponseEntity.status(code.httpStatus())
                .header("Cache-Control", "no-store")
                .body(new ErrorResponse(body));
    }
}
