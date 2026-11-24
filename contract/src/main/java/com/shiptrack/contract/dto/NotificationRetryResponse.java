package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.NotificationStatus;

/**
 * Response of {@code POST /notifications/{id}/retry}.
 *
 * <p>Source: 接口文档_终稿.md §5.14</p>
 */
public record NotificationRetryResponse(
        @JsonProperty("id") long id,
        @JsonProperty("status") NotificationStatus status,
        @JsonProperty("retry_count") int retryCount
) {
}
