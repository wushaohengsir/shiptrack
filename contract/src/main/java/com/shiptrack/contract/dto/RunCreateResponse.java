package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.AttemptStatus;
import com.shiptrack.contract.enums.RunTrigger;

/**
 * Response of {@code POST /runs}.
 *
 * <p>When no active container exists the API returns {@code created=false} with a
 * {@code reason}; otherwise the run fields are populated.
 * Source: 接口文档_终稿.md §5.11 / §5.21</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunCreateResponse(
        @JsonProperty("run_id") String runId,
        @JsonProperty("trigger") RunTrigger trigger,
        @JsonProperty("status") AttemptStatus status,
        @JsonProperty("container_count") Integer containerCount,
        @JsonProperty("created") Boolean created,
        @JsonProperty("reason") String reason
) {
}
