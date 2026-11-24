package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Standard paginated response envelope.
 *
 * <p>Source: 接口文档_终稿.md §1.3</p>
 */
public record PageResponse<T>(
        @JsonProperty("items") List<T> items,
        @JsonProperty("page") int page,
        @JsonProperty("page_size") int pageSize,
        @JsonProperty("total") long total
) {
}
