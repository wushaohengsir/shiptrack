package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body of {@code POST /runs}; MVP only accepts {@code scope=all}.
 *
 * <p>Source: 接口文档_终稿.md §5.11</p>
 */
public record RunCreateRequest(
        @JsonProperty("scope") String scope
) {
}
