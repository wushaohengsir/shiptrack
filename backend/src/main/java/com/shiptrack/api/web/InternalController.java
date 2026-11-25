package com.shiptrack.api.web;

import com.shiptrack.api.service.InternalTaskService;
import com.shiptrack.api.service.ResultReportService;
import com.shiptrack.contract.internal.TrackingResultReport;
import com.shiptrack.contract.internal.TrackingResultResponse;
import com.shiptrack.contract.internal.TrackingRunsResponse;
import com.shiptrack.contract.internal.TrackingTaskClaimRequest;
import com.shiptrack.contract.internal.TrackingTaskClaimResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Collector-facing endpoints (§5.22-5.24). Guarded by {@code X-Internal-Token};
 * Nginx must never proxy this prefix.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final InternalTaskService tasks;
    private final ResultReportService results;

    public InternalController(InternalTaskService tasks, ResultReportService results) {
        this.tasks = tasks;
        this.results = results;
    }

    @GetMapping("/tracking-runs")
    public ResponseEntity<TrackingRunsResponse> discover(
            @RequestParam(required = false, defaultValue = "claimable") String state,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        if (!"claimable".equalsIgnoreCase(state)) {
            throw new com.shiptrack.api.error.ApiException(
                    com.shiptrack.contract.enums.ErrorCode.VALIDATION_ERROR,
                    "state 只支持 claimable");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(tasks.discoverClaimable(limit));
    }

    @PostMapping("/tracking-tasks")
    public ResponseEntity<TrackingTaskClaimResponse> claim(
            @RequestBody TrackingTaskClaimRequest request) {
        if (request == null || request.runId() == null) {
            throw new com.shiptrack.api.error.ApiException(
                    com.shiptrack.contract.enums.ErrorCode.VALIDATION_ERROR, "run_id 必填");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(tasks.claim(request.runId(), request.limit()));
    }

    @PostMapping("/tracking-results")
    public TrackingResultResponse report(@RequestBody TrackingResultReport report) {
        return results.report(report);
    }
}
