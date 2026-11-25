package com.shiptrack.api.web;

import com.shiptrack.api.service.RunService;
import com.shiptrack.contract.dto.RunCreateRequest;
import com.shiptrack.contract.dto.RunCreateResponse;
import com.shiptrack.contract.dto.RunNotCreatedResponse;
import com.shiptrack.contract.dto.RunResultDto;
import com.shiptrack.contract.enums.AttemptStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) RunCreateRequest request) {
        if (request != null && request.scope() != null && !"all".equalsIgnoreCase(request.scope())) {
            throw new com.shiptrack.api.error.ApiException(
                    com.shiptrack.contract.enums.ErrorCode.VALIDATION_ERROR,
                    "scope 目前只支持 all");
        }
        RunService.RunOutcome outcome = runs.refreshAll();
        if (!outcome.created()) {
            return ResponseEntity.ok(new RunNotCreatedResponse(false, outcome.reason()));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RunCreateResponse(
                outcome.runId(), outcome.trigger(), AttemptStatus.QUEUED,
                outcome.containerCount(), true, null));
    }

    @GetMapping("/{runId}")
    public RunResultDto result(@PathVariable String runId) {
        return runs.runResult(runId);
    }
}
