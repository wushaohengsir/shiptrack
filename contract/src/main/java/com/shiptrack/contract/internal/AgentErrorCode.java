package com.shiptrack.contract.internal;

/**
 * Error codes the collector may report for a failed attempt.
 *
 * <p>Source: 接口文档_终稿.md §6.3</p>
 */
public enum AgentErrorCode {
    CONTAINER_NOT_FOUND,
    SOURCE_UNAVAILABLE,
    TIMEOUT,
    PARSE_ERROR,
    CAPTCHA_REQUIRED,
    LOGIN_REQUIRED,
    RATE_LIMITED,
    LOCKED,
    UNKNOWN_ERROR
}
