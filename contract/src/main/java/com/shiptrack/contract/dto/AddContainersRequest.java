package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request body of {@code POST /orders/{id}/containers}.
 *
 * <p>Source: 接口文档_终稿.md §5.6</p>
 */
public record AddContainersRequest(
        @JsonProperty("containers") List<ContainerCreateRequest> containers,
        @JsonProperty("force_invalid_containers") Boolean forceInvalidContainers
) {
}
