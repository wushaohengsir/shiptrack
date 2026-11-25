package com.shiptrack.api.web;

import com.shiptrack.api.service.OrderService;
import com.shiptrack.api.service.RunService;
import com.shiptrack.api.service.TimelineService;
import com.shiptrack.contract.dto.RefreshContainerResponse;
import com.shiptrack.contract.dto.UpdateContainerRequest;
import com.shiptrack.contract.dto.UpdateContainerResponse;
import com.shiptrack.contract.enums.AttemptStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/containers")
public class ContainerController {

    private final OrderService orders;
    private final RunService runs;
    private final TimelineService timeline;

    public ContainerController(OrderService orders, RunService runs, TimelineService timeline) {
        this.orders = orders;
        this.runs = runs;
        this.timeline = timeline;
    }

    @PatchMapping("/{id}")
    public UpdateContainerResponse update(@PathVariable long id,
                                          @RequestBody UpdateContainerRequest request) {
        return orders.updateContainer(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable long id) {
        orders.deactivateContainer(id);
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<RefreshContainerResponse> refresh(@PathVariable long id) {
        RunService.RunOutcome outcome = runs.refreshContainer(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RefreshContainerResponse(
                outcome.runId(), id, AttemptStatus.QUEUED));
    }

    @GetMapping("/{id}/timeline")
    public Map<String, Object> timeline(
            @PathVariable long id,
            @RequestParam(required = false) String types,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(name = "page_size", required = false, defaultValue = "20") int pageSize) {
        return timeline.timeline(id, TimelineService.parseTypes(types), page, pageSize);
    }
}
