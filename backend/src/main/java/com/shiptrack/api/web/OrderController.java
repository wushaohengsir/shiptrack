package com.shiptrack.api.web;

import com.shiptrack.api.repo.OrderFilter;
import com.shiptrack.api.service.OrderService;
import com.shiptrack.api.service.RunService;
import com.shiptrack.contract.dto.AddContainersRequest;
import com.shiptrack.contract.dto.ContainerDto;
import com.shiptrack.contract.dto.CreateOrderRequest;
import com.shiptrack.contract.dto.OrderDetailResponse;
import com.shiptrack.contract.dto.OrderListItemDto;
import com.shiptrack.contract.dto.PageResponse;
import com.shiptrack.contract.dto.RefreshOrderResponse;
import com.shiptrack.contract.dto.RunNotCreatedResponse;
import com.shiptrack.contract.dto.UpdateOrderRequest;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orders;
    private final RunService runs;

    public OrderController(OrderService orders, RunService runs) {
        this.orders = orders;
        this.runs = runs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetailResponse create(@RequestBody CreateOrderRequest request) {
        return orders.create(request);
    }

    @GetMapping
    public PageResponse<OrderListItemDto> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "transport_status", required = false) String transportStatus,
            @RequestParam(name = "eta_risk", required = false) String etaRisk,
            @RequestParam(name = "tracking_health", required = false) String trackingHealth,
            @RequestParam(name = "include_archived", required = false, defaultValue = "false")
            boolean includeArchived,
            @RequestParam(required = false, defaultValue = "risk_desc") String sort,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(name = "page_size", required = false, defaultValue = "20") int pageSize) {
        return orders.list(new OrderFilter(keyword, transportStatus, etaRisk, trackingHealth,
                includeArchived, sort, page, pageSize));
    }

    @GetMapping("/{id}")
    public OrderDetailResponse detail(@PathVariable long id) {
        return orders.detail(id);
    }

    @PatchMapping("/{id}")
    public OrderDetailResponse update(@PathVariable long id, @RequestBody UpdateOrderRequest request) {
        return orders.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable long id) {
        orders.archive(id);
    }

    @PostMapping("/{id}/containers")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ContainerDto> addContainers(@PathVariable long id,
                                            @RequestBody AddContainersRequest request) {
        return orders.addContainers(id, request);
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<?> refresh(@PathVariable long id) {
        RunService.RunOutcome outcome = runs.refreshOrder(id);
        if (!outcome.created()) {
            return ResponseEntity.ok(new RunNotCreatedResponse(false, outcome.reason()));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RefreshOrderResponse(
                outcome.runId(), id, AttemptStatus.QUEUED, outcome.containerCount()));
    }
}
