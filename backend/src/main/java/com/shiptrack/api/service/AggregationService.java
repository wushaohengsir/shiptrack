package com.shiptrack.api.service;

import com.shiptrack.api.repo.ContainerRepository;
import com.shiptrack.api.repo.EventRecord;
import com.shiptrack.api.repo.EventRepository;
import com.shiptrack.api.repo.NotificationRepository;
import com.shiptrack.api.repo.OrderRepository;
import com.shiptrack.api.repo.SnapshotRepository;
import com.shiptrack.api.support.JsonStore;
import com.shiptrack.contract.enums.EventType;
import com.shiptrack.contract.enums.NotificationStatus;
import com.shiptrack.contract.enums.OrderNotificationState;
import com.shiptrack.contract.internal.OrderNotificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Order-level aggregation after the last terminal result of a run (§8.6).
 *
 * <p>Only the database side is implemented in M3: the notification row and the
 * event links are written as {@code pending}. A Feishu dispatcher can poll that
 * queue (see {@link #dispatchHook()}); sending is out of scope.</p>
 */
@Service
public class AggregationService {

    private static final Logger log = LoggerFactory.getLogger(AggregationService.class);

    private final SnapshotRepository snapshots;
    private final EventRepository events;
    private final NotificationRepository notifications;
    private final ContainerRepository containers;
    private final OrderRepository orders;
    private final AppConfigService config;
    private final JsonStore jsonStore;

    public AggregationService(SnapshotRepository snapshots, EventRepository events,
                              NotificationRepository notifications, ContainerRepository containers,
                              OrderRepository orders, AppConfigService config, JsonStore jsonStore) {
        this.snapshots = snapshots;
        this.events = events;
        this.notifications = notifications;
        this.containers = containers;
        this.orders = orders;
        this.config = config;
        this.jsonStore = jsonStore;
    }

    /**
     * Must be called after the last terminal write. Returns
     * {@code pending_order_aggregation} while any row of the order's run is
     * still queued/running.
     */
    public OrderNotificationResult aggregate(long orderId, String runId) {
        if (snapshots.countNonTerminal(orderId, runId) > 0) {
            return new OrderNotificationResult(
                    OrderNotificationState.PENDING_ORDER_AGGREGATION, null, null);
        }
        AppConfigService.AppConfigValues values = config.values();
        List<EventRecord> eligible = new ArrayList<>();
        for (EventRecord event : events.findForOrderRun(orderId, runId)) {
            if (isEligible(event, values)) {
                eligible.add(event);
            }
        }
        if (eligible.isEmpty()) {
            return new OrderNotificationResult(OrderNotificationState.NOT_APPLICABLE, null, null);
        }
        String orderNo = orders.findById(orderId).map(order -> order.orderNo()).orElse("order-" + orderId);
        String key = "order:" + orderId + ":run:" + runId + ":channel:feishu";
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("title", "【船期变化】" + orderNo);
        payload.put("lines", lines(eligible));
        payload.put("queried_at", OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
        payload.put("source", "MSC");
        long notificationId = notifications.insertPendingIfAbsent(orderId, key, runId,
                jsonStore.write(payload));
        events.linkNotification(eligible.stream().map(EventRecord::id).toList(), notificationId);
        log.info("notification queued: {} ({} events)", key, eligible.size());
        return new OrderNotificationResult(
                OrderNotificationState.CREATED, notificationId, NotificationStatus.PENDING);
    }

    /**
     * Deliberately empty seam for the §7 Feishu dispatcher (out of scope in M3).
     */
    public void dispatchHook() {
    }

    private boolean isEligible(EventRecord event, AppConfigService.AppConfigValues values) {
        return switch (event.eventType()) {
            case STATUS_CHANGED, STATUS_CORRECTED -> values.notifyOnStatusChange();
            case ETA_CHANGED, ETA_RECOVERED -> true;
            case QUERY_FAILED, DATA_STALE, QUERY_RECOVERED -> true;
        };
    }

    private List<String> lines(List<EventRecord> events) {
        List<String> lines = new ArrayList<>();
        for (EventRecord event : events) {
            String containerNo = containers.findById(event.containerId())
                    .map(container -> container.containerNo())
                    .orElse("container-" + event.containerId());
            lines.add(containerNo + "  " + describe(event));
        }
        return lines;
    }

    private String describe(EventRecord event) {
        EventType type = event.eventType();
        return switch (type) {
            case ETA_CHANGED -> "到港时间变更 " + valueOf(event.oldValueJson()) + " → "
                    + valueOf(event.newValueJson());
            case ETA_RECOVERED -> "到港时间已恢复 " + valueOf(event.newValueJson());
            case STATUS_CHANGED -> "运输状态更新为 " + valueOf(event.newValueJson());
            case STATUS_CORRECTED -> "运输状态修正为 " + valueOf(event.newValueJson());
            case QUERY_FAILED -> "查询失败，数据可能过期";
            case DATA_STALE -> "数据已过期，请人工核对";
            case QUERY_RECOVERED -> "查询已恢复";
        };
    }

    private String valueOf(String json) {
        if (json == null) {
            return "-";
        }
        Map<String, Object> map = jsonStore.readMap(json);
        if (map == null) {
            return json;
        }
        Object date = map.get("date");
        Object status = map.get("transport_status");
        if (date != null) {
            return date.toString();
        }
        return status == null ? json : status.toString();
    }
}
