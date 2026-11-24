package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Error envelope used by every non-2xx response.
 *
 * <p>Source: 接口文档_终稿.md §1.4</p>
 */
public record ErrorResponse(
        @JsonProperty("error") ApiError error
) {
}
