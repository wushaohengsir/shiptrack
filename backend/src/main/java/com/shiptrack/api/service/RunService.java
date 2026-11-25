package com.shiptrack.api.service;

import com.shiptrack.api.config.ApiProperties;
import com.shiptrack.api.error.ApiException;
import com.shiptrack.api.repo.ContainerRecord;
import com.shiptrack.api.repo.ContainerRepository;
import com.shiptrack.api.repo.NotificationRepository;
import com.shiptrack.api.repo.OrderRecord;
import com.shiptrack.api.repo.OrderRepository;
import com.shiptrack.api.repo.SnapshotRepository;
import com.shiptrack.api.support.Utc;
import com.shiptrack.contract.dto.RunNotificationCounts;
import com.shiptrack.contract.dto.RunResultDto;
import com.shiptrack.contract.enums.ErrorCode;
import com.shiptrack.contract.enums.NotCreatedReason;
import com.shiptrack.contract.enums.RunTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Creates query batches ({@code run_id}) and exposes their result (§5.9-5.11,
 * §5.21). A batch is only the pre-created {@code tracking_snapshots} rows; the
 * collector never writes a new row.
 */
@Service
public class RunService {

    private final ContainerRepository containers;
    private final OrderRepository orders;
    private final SnapshotRepository snapshots;
    private final NotificationRepository notifications;
    private final AggregationService aggregation;
    private final ApiProperties properties;

    public RunService(ContainerRepository containers, OrderRepository orders,
                      SnapshotRepository snapshots, NotificationRepository notifications,
                      AggregationService aggregation, ApiProperties properties) {
        this.containers = containers;
        this.orders = orders;
        this.snapshots = snapshots;
        this.notifications = notifications;
        this.aggregation = aggregation;
        this.properties = properties;
    }

    @Transactional
    public RunOutcome createRun(RunTrigger trigger, List<Long> containerIds) {
        Timestamp now = Utc.now();
        String runId = com.shiptrack.api.support.Ulid.newRunId();
        int createdRows = 0;
        Set<Long> touchedOrders = new LinkedHashSet<>();
        for (long containerId : containerIds) {
            Optional<ContainerRecord> found = containers.findById(containerId);
            if (found.isEmpty()) {
                continue;
            }
            ContainerRecord container = found.get();
            Optional<OrderRecord> order = orders.findById(container.orderId());
            String skipCode = null;
            if (order.isEmpty() || order.get().archivedAt() != null) {
                skipCode = "ORDER_ARCHIVED";
            } else if (!container.active()) {
                skipCode = "INACTIVE_CONTAINER";
            } else if (isLockedByOther(container, now)) {
                skipCode = "LOCKED";
            }
            if (skipCode != null) {
                snapshots.insertSkipped(containerId, runId, trigger, skipCode, now);
            } else {
                snapshots.insertQueued(containerId, runId, trigger, now);
            }
            createdRows++;
            touchedOrders.add(container.orderId());
        }
        if (createdRows == 0) {
            return RunOutcome.notCreated(NotCreatedReason.NO_ACTIVE_CONTAINERS);
        }
        for (long orderId : touchedOrders) {
            aggregation.aggregate(orderId, runId);
        }
        return new RunOutcome(true, runId, trigger, createdRows, null);
    }

    /** §5.9: a locked container returns 423 and creates no row at all. */
    @Transactional
    public RunOutcome refreshContainer(long containerId) {
        ContainerRecord container = containers.findById(containerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "箱号不存在：" + containerId));
        OrderRecord order = orders.findById(container.orderId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "订单不存在"));
        if (order.archivedAt() != null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "订单已归档");
        }
        if (!container.active()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "箱号已停用");
        }
        if (isLockedByOther(container, Utc.now())) {
            throw new ApiException(ErrorCode.LOCKED, "该箱号正在被其他任务查询");
        }
        return createRun(RunTrigger.MANUAL_CONTAINER, List.of(containerId));
    }

    @Transactional
    public RunOutcome refreshOrder(long orderId) {
        OrderRecord order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "订单不存在：" + orderId));
        if (order.archivedAt() != null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "订单已归档");
        }
        List<Long> ids = containers.findActiveByOrderId(orderId).stream()
                .map(ContainerRecord::id).toList();
        if (ids.isEmpty()) {
            return RunOutcome.notCreated(NotCreatedReason.NO_ACTIVE_CONTAINERS);
        }
        return createRun(RunTrigger.MANUAL_ORDER, ids);
    }

    @Transactional
    public RunOutcome refreshAll() {
        List<Long> ids = containers.findAllActiveIds();
        if (ids.isEmpty()) {
            return RunOutcome.notCreated(NotCreatedReason.NO_ACTIVE_CONTAINERS);
        }
        return createRun(RunTrigger.MANUAL_ALL, ids);
    }

    @Transactional
    public RunOutcome scheduledRun() {
        List<Long> ids = containers.findAllActiveIds();
        if (ids.isEmpty()) {
            return RunOutcome.notCreated(NotCreatedReason.NO_ACTIVE_CONTAINERS);
        }
        return createRun(RunTrigger.SCHEDULE, ids);
    }

    public RunResultDto runResult(String runId) {
        if (!snapshots.runExists(runId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "批次不存在：" + runId);
        }
        SnapshotRepository.RunCounts counts = snapshots.runCounts(runId);
        RunTrigger trigger = snapshots.findRunTrigger(runId).orElse(RunTrigger.SCHEDULE);
        NotificationRepository.RunNotificationCounts notificationCounts =
                notifications.countsForRun(runId);
        String status;
        if (counts.count("queued") > 0) {
            status = "queued";
        } else if (counts.count("running") > 0) {
            status = "running";
        } else {
            status = "completed";
        }
        OffsetDateTime finished = "completed".equals(status) ? counts.finishedAt() : null;
        return new RunResultDto(
                runId,
                trigger,
                status,
                counts.startedAt(),
                finished,
                counts.total(),
                counts.count("queued"),
                counts.count("running"),
                counts.count("success"),
                counts.count("failed"),
                counts.count("skipped"),
                new RunNotificationCounts(notificationCounts.created(),
                        notificationCounts.sent(), notificationCounts.failed()));
    }

    private boolean isLockedByOther(ContainerRecord container, Timestamp now) {
        if (container.lockRunId() == null || container.lockAcquiredAt() == null) {
            return false;
        }
        long timeout = properties.getTaskClaimTimeoutMs();
        OffsetDateTime lockedUntil = container.lockAcquiredAt().plus(java.time.Duration.ofMillis(timeout));
        return lockedUntil.isAfter(Utc.at(now));
    }

    public record RunOutcome(boolean created, String runId, RunTrigger trigger,
                             int containerCount, NotCreatedReason reason) {

        public static RunOutcome notCreated(NotCreatedReason reason) {
            return new RunOutcome(false, null, null, 0, reason);
        }
    }
}
