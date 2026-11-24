package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.ErrorCode;

import java.util.Map;

/**
 * Error body. {@code details} is optional and may be null.
 *
 * <p>Source: 接口文档_终稿.md §1.4</p>
 */
public record ApiError(
        @JsonProperty("code") ErrorCode code,
        @JsonProperty("message") String message,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("details") Map<String, Object> details
) {
}
