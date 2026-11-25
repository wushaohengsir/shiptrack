package com.shiptrack.api.error;

import com.shiptrack.contract.enums.ErrorCode;

import java.util.Map;

/**
 * An error that already knows its documented {@link ErrorCode} (§1.4).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public int httpStatus() {
        return code.httpStatus();
    }
}
