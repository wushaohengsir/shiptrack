package com.shiptrack.api.service;

import com.shiptrack.api.repo.ContainerRecord;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Order-level aggregation after the last terminal result of a run (§8.6).
 *
 * <p>This service only writes the database side: the {@code pending}
 * notification row and its event links. {@code NotificationDispatchService}
 * consumes that queue after the transaction commits (§8.6).</p>
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
     * §10.3 notification conditions, with the M4 arrival rule: a transition to
     * {@code ARRIVED_POD} always notifies, even when ordinary status-change
     * notifications are disabled.
     */
    private boolean isEligible(EventRecord event, AppConfigService.AppConfigValues values) {
        return switch (event.eventType()) {
            case STATUS_CHANGED, STATUS_CORRECTED ->
                    values.notifyOnStatusChange() || isArrivedPod(event);
            case ETA_CHANGED, ETA_RECOVERED -> true;
            case QUERY_FAILED, DATA_STALE, QUERY_RECOVERED -> true;
        };
    }

    private boolean isArrivedPod(EventRecord event) {
        Map<String, Object> value = jsonStore.readMap(event.newValueJson());
        return value != null && "ARRIVED_POD".equals(text(value, "transport_status"));
    }

    private List<String> lines(List<EventRecord> events) {
        List<String> lines = new ArrayList<>();
        for (EventRecord event : events) {
            ContainerRecord container = containers.findById(event.containerId()).orElse(null);
            String containerNo = container == null ? "container-" + event.containerId()
                    : container.containerNo();
            StringBuilder line = new StringBuilder(containerNo)
                    .append("  ").append(describe(event, container));
            String route = route(container);
            if (route != null) {
                line.append("  ").append(route);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private String describe(EventRecord event, ContainerRecord container) {
        EventType type = event.eventType();
        return switch (type) {
            case ETA_CHANGED, ETA_RECOVERED -> etaText(event, type);
            case STATUS_CHANGED, STATUS_CORRECTED -> statusText(event, type, container);
            case QUERY_FAILED -> "查询失败，数据可能过期";
            case DATA_STALE -> "数据已过期，请人工核对";
            case QUERY_RECOVERED -> "查询已恢复";
        };
    }

    /** §7.1: {@code 到港 old → new（较上次…；较基线累计…；当前风险…）}. */
    private String etaText(EventRecord event, EventType type) {
        Map<String, Object> oldValue = jsonStore.readMap(event.oldValueJson());
        Map<String, Object> newValue = jsonStore.readMap(event.newValueJson());
        String oldDate = dateOf(oldValue);
        String newDate = dateOf(newValue);

        StringBuilder text = new StringBuilder("到港");
        if (type == EventType.ETA_RECOVERED) {
            text.append("已恢复");
            if (newDate != null) {
                text.append(' ').append(newDate);
            }
        } else {
            if (oldDate != null) {
                text.append(' ').append(oldDate).append(" → ");
            }
            if (newDate != null) {
                text.append(newDate);
            }
        }

        List<String> parts = new ArrayList<>();
        String last = lastChange(oldDate, newDate);
        if (last != null) {
            parts.add(last);
        }
        String baseline = baselineChange(event.baselineDeltaJson());
        if (baseline != null) {
            parts.add(baseline);
        }
        String risk = riskText(event.riskAfter());
        if (risk != null) {
            parts.add(risk);
        }
        if (!parts.isEmpty()) {
            text.append("（").append(String.join("；", parts)).append("）");
        }
        return text.toString();
    }

    private String statusText(EventRecord event, EventType type, ContainerRecord container) {
        Map<String, Object> value = jsonStore.readMap(event.newValueJson());
        String status = value == null ? null : text(value, "transport_status");
        if ("ARRIVED_POD".equals(status)) {
            String date = arrivalDate(event, container);
            return date == null ? "已到港" : "已到港（" + date + "）";
        }
        String label = status == null ? valueOf(event.newValueJson()) : status;
        return (type == EventType.STATUS_CHANGED ? "运输状态更新为 " : "运输状态修正为 ") + label;
    }

    /** Actual source-event date first, then the tracked (planned) date, else omitted. */
    private String arrivalDate(EventRecord event, ContainerRecord container) {
        if (event.sourceEventAt() != null) {
            return event.sourceEventAt().toLocalDate().toString();
        }
        if (container != null && container.trackedEtaDate() != null) {
            return container.trackedEtaDate().toString();
        }
        return null;
    }

    /** Only when both ends are present, so a half-known route is omitted deterministically. */
    private String route(ContainerRecord container) {
        if (container == null || container.pol() == null || container.pol().isBlank()
                || container.pod() == null || container.pod().isBlank()) {
            return null;
        }
        return "航线：" + container.pol() + " → " + container.pod();
    }

    private String lastChange(String oldDate, String newDate) {
        if (oldDate == null || newDate == null) {
            return null;
        }
        Long days = daysBetween(oldDate, newDate);
        if (days == null || days == 0) {
            return null;
        }
        return days > 0 ? "较上次推迟 " + days + " 天" : "较上次提前 " + (-days) + " 天";
    }

    private Long daysBetween(String from, String to) {
        try {
            return java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.parse(from), LocalDate.parse(to));
        } catch (java.time.format.DateTimeParseException error) {
            return null;
        }
    }

    private String baselineChange(String json) {
        Map<String, Object> delta = jsonStore.readMap(json);
        if (delta == null || !(delta.get("value") instanceof Number number)) {
            return null;
        }
        long amount = number.longValue();
        String unit = delta.get("unit") == null ? "day" : delta.get("unit").toString();
        String noun = "hour".equals(unit) ? "小时" : "天";
        if (amount > 0) {
            return "较基线累计推迟 " + amount + " " + noun;
        }
        if (amount < 0) {
            return "较基线累计提前 " + (-amount) + " " + noun;
        }
        return "较基线累计 0 " + noun;
    }

    private String riskText(com.shiptrack.contract.enums.EtaRisk risk) {
        if (risk == null) {
            return null;
        }
        return switch (risk) {
            case DELAYED -> "当前风险 延误";
            case EARLY -> "当前风险 提前";
            case NORMAL -> "当前风险 正常";
            case NO_ETA -> "当前风险 无 ETA";
        };
    }

    private String dateOf(Map<String, Object> value) {
        return value == null ? null : text(value, "date");
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

    private static String text(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item == null ? null : item.toString();
    }
}
