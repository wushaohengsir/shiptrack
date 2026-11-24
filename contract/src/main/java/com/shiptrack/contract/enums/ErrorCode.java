package com.shiptrack.contract.enums;

/**
 * Stable API error codes and their HTTP status.
 *
 * <p>Source: 接口文档_终稿.md §1.4</p>
 */
public enum ErrorCode {
    VALIDATION_ERROR(400),
    CONTAINER_CHECKSUM_INVALID(400),
    UNAUTHORIZED(401),
    NOT_FOUND(404),
    DUPLICATE_ORDER_NO(409),
    DUPLICATE_CONTAINER(409),
    CONFLICT(409),
    TASK_NOT_IN_RUN(409),
    STALE_CLAIM(409),
    LOCKED(423),
    UPSTREAM_ERROR(502),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
