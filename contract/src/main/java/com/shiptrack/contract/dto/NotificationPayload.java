package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Rendered Feishu message body stored on a notification row.
 *
 * <p>Source: 接口文档_终稿.md §3.7 / §7.1</p>
 */
public record NotificationPayload(
        @JsonProperty("title") String title,
        @JsonProperty("lines") List<String> lines,
        @JsonProperty("queried_at") OffsetDateTime queriedAt,
        @JsonProperty("source") String source
) {
}
