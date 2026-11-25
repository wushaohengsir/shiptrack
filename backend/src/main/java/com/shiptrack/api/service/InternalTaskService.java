package com.shiptrack.api.service;

import com.shiptrack.api.config.ApiProperties;
import com.shiptrack.api.error.ApiException;
import com.shiptrack.api.repo.ContainerRecord;
import com.shiptrack.api.repo.ContainerRepository;
import com.shiptrack.api.repo.OrderRecord;
import com.shiptrack.api.repo.OrderRepository;
import com.shiptrack.api.repo.SnapshotRecord;
import com.shiptrack.api.repo.SnapshotRepository;
import com.shiptrack.api.support.Ulid;
import com.shiptrack.api.support.Utc;
import com.shiptrack.contract.enums.ErrorCode;
import com.shiptrack.contract.enums.RunTrigger;
import com.shiptrack.contract.internal.ClaimedTaskDto;
import com.shiptrack.contract.internal.TrackingRunDto;
import com.shiptrack.contract.internal.TrackingRunsResponse;
import com.shiptrack.contract.internal.TrackingTaskClaimResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code /internal/tracking-runs} and {@code /internal/tracking-tasks}
 * (§5.24 / §5.22). The cross-batch lease lives on {@code containers.lock_run_id}
 * and is always taken inside the same transaction as the {@code queued →
 * running} update.
 */
@Service
public class InternalTaskService {

    private final ContainerRepository containers;
    private final OrderRepository orders;
    private final SnapshotRepository snapshots;
    private final AggregationService aggregation;
    private final AppConfigService config;
    private final ApiProperties properties;

    public InternalTaskService(ContainerRepository containers, OrderRepository orders,
                               SnapshotRepository snapshots, AggregationService aggregation,
                               AppConfigService config, ApiProperties properties) {
        this.containers = containers;
        this.orders = orders;
        this.snapshots = snapshots;
        this.aggregation = aggregation;
        this.config = config;
        this.properties = properties;
    }

    public TrackingRunsResponse discoverClaimable(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<TrackingRunDto> runs = snapshots.findClaimableRuns(safeLimit).stream()
                .map(run -> new TrackingRunDto(run.runId(), run.trigger(),
                        run.queuedCount(), run.oldestQueuedAt()))
                .toList();
        return new TrackingRunsResponse(runs);
    }

    @Transactional
    public TrackingTaskClaimResponse claim(String runId, Integer requestedLimit) {
        if (runId == null || runId.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "run_id 必填");
        }
        RunTrigger trigger = snapshots.findRunTrigger(runId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "批次不存在：" + runId));
        int limit = requestedLimit == null ? 1 : Math.min(Math.max(requestedLimit, 1), 20);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Timestamp timeoutBefore = Timestamp.from(
                now.minus(Duration.ofMillis(properties.getTaskClaimTimeoutMs())).toInstant());
        // Only the expired rows' leases are released; other containers of this
        // run may still be executing in the same agent.
        for (long containerId : snapshots.findTimedOutContainerIds(runId, timeoutBefore)) {
            containers.releaseLock(containerId, runId);
        }
        snapshots.reclaimTimedOutForRun(runId, timeoutBefore);

        List<ClaimedTaskDto> items = new ArrayList<>();
        Set<Long> ordersToAggregate = new LinkedHashSet<>();
        for (SnapshotRecord candidate : snapshots.findQueued(runId, limit)) {
            OffsetDateTime claimAt = OffsetDateTime.now(ZoneOffset.UTC);
            Optional<ContainerRecord> found = containers.findByIdForUpdate(candidate.containerId());
            if (found.isEmpty()) {
                snapshots.markSkipped(candidate.id(), "UNKNOWN_ERROR", Utc.timestamp(claimAt));
                continue;
            }
            ContainerRecord container = found.get();
            Optional<OrderRecord> order = orders.findById(container.orderId());
            if (order.isEmpty() || order.get().archivedAt() != null) {
                snapshots.markSkipped(candidate.id(), "ORDER_ARCHIVED", Utc.timestamp(claimAt));
                ordersToAggregate.add(container.orderId());
                continue;
            }
            if (!container.active()) {
                snapshots.markSkipped(candidate.id(), "INACTIVE_CONTAINER", Utc.timestamp(claimAt));
                ordersToAggregate.add(container.orderId());
                continue;
            }
            if (!takeLease(container, runId, claimAt)) {
                snapshots.markSkipped(candidate.id(), "LOCKED", Utc.timestamp(claimAt));
                ordersToAggregate.add(container.orderId());
                continue;
            }
            String claimToken = Ulid.newClaimToken();
            int updated = snapshots.markClaimed(candidate.id(), claimToken, Utc.timestamp(claimAt));
            if (updated == 0) {
                containers.releaseLock(container.id(), runId);
                continue;
            }
            items.add(new ClaimedTaskDto(
                    container.id(), container.containerNo(), container.carrier(),
                    container.trackedMilestone(), container.baselineSnapshotId(),
                    container.lastSuccessAt(), claimToken,
                    config.values().maxRetryPerRun()));
        }
        for (long orderId : ordersToAggregate) {
            aggregation.aggregate(orderId, runId);
        }
        return new TrackingTaskClaimResponse(runId, trigger, items);
    }

    /** §5.22: take, keep or take over the container lease. */
    private boolean takeLease(ContainerRecord container, String runId, OffsetDateTime now) {
        String lockRunId = container.lockRunId();
        if (lockRunId == null || lockRunId.equals(runId)) {
            containers.acquireLock(container.id(), runId);
            return true;
        }
        boolean expired = container.lockAcquiredAt() == null
                || !container.lockAcquiredAt()
                        .plus(Duration.ofMillis(properties.getTaskClaimTimeoutMs())).isAfter(now);
        if (!expired) {
            return false;
        }
        snapshots.reclaimRunningRow(container.id(), lockRunId);
        containers.acquireLock(container.id(), runId);
        return true;
    }
}
