package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request body of {@code POST /orders}.
 *
 * <p>Source: 接口文档_终稿.md §5.1</p>
 */
public record CreateOrderRequest(
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("title") String title,
        @JsonProperty("containers") List<ContainerCreateRequest> containers,
        @JsonProperty("force_invalid_containers") Boolean forceInvalidContainers
) {
}
