package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Partial update body of {@code PATCH /orders/{id}}; null fields are left unchanged.
 *
 * <p>Source: 接口文档_终稿.md §5.4</p>
 */
public record UpdateOrderRequest(
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("title") String title
) {
}
