package com.shiptrack.api.service;

import com.shiptrack.api.repo.ContainerRecord;
import com.shiptrack.api.repo.ContainerRepository;
import com.shiptrack.api.repo.EventRecord;
import com.shiptrack.api.repo.EventRepository;
import com.shiptrack.api.repo.NotificationRepository;
import com.shiptrack.api.repo.OrderRepository;
import com.shiptrack.api.repo.SnapshotRecord;
import com.shiptrack.api.repo.SnapshotRepository;
import com.shiptrack.api.support.JsonStore;
import com.shiptrack.contract.dto.SourceEvent;
import com.shiptrack.contract.enums.EventTimeType;
import com.shiptrack.contract.enums.EventType;
import com.shiptrack.contract.enums.NotificationStatus;
import com.shiptrack.contract.enums.OrderNotificationState;
import com.shiptrack.contract.enums.TransportStatus;
import com.shiptrack.contract.internal.OrderNotificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
                    .append("  ").append(describe(event));
            String route = route(container);
            if (route != null) {
                line.append("  ").append(route);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private String describe(EventRecord event) {
        EventType type = event.eventType();
        return switch (type) {
            case ETA_CHANGED, ETA_RECOVERED -> etaText(event, type);
            case STATUS_CHANGED, STATUS_CORRECTED -> statusText(event, type);
            case QUERY_FAILED -> "查询失败，数据可能过期";
            case DATA_STALE -> "数据已过期，请人工核对";
            case QUERY_RECOVERED -> "查询已恢复";
        };
    }

    /** §7.1: {@code 到港 old → new（较上次…；较基线累计…；当前风险…）}. */
    private String etaText(EventRecord event, EventType type) {
        Map<String, Object> oldValue = jsonStore.readMap(event.oldValueJson());
        Map<String, Object> newValue = jsonStore.readMap(event.newValueJson());
        String oldDisplay = etaDisplay(oldValue);
        String newDisplay = etaDisplay(newValue);

        StringBuilder text = new StringBuilder("到港");
        if (type == EventType.ETA_RECOVERED) {
            text.append("已恢复");
            if (newDisplay != null) {
                text.append(' ').append(newDisplay);
            }
        } else {
            if (oldDisplay != null) {
                text.append(' ').append(oldDisplay).append(" → ");
            }
            if (newDisplay != null) {
                text.append(newDisplay);
            }
        }

        List<String> parts = new ArrayList<>();
        String last = lastChange(oldValue, newValue);
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

    private String statusText(EventRecord event, EventType type) {
        Map<String, Object> value = jsonStore.readMap(event.newValueJson());
        String status = value == null ? null : text(value, "transport_status");
        if ("ARRIVED_POD".equals(status)) {
            String date = arrivalDate(event);
            // Only an actual arrival trajectory may carry a date; the tracked
            // (planned) ETA is never presented as the arrival date.
            return date == null ? "已到港" : "已到港（" + date + "）";
        }
        String label = status == null ? valueOf(event.newValueJson()) : status;
        return (type == EventType.STATUS_CHANGED ? "运输状态更新为 " : "运输状态修正为 ") + label;
    }

    /**
     * Actual arrival date: the event's own {@code source_event_at} first, then
     * the ARRIVED_POD ACTUAL trajectory inside this attempt's snapshot. Falls
     * back to omitting the date: a missing actual trajectory must never be
     * replaced by the planned ETA.
     */
    private String arrivalDate(EventRecord event) {
        if (event.sourceEventAt() != null) {
            return event.sourceEventAt().toLocalDate().toString();
        }
        if (event.toSnapshotId() == null) {
            return null;
        }
        SnapshotRecord snapshot = snapshots.findById(event.toSnapshotId()).orElse(null);
        if (snapshot == null) {
            return null;
        }
        for (SourceEvent source : jsonStore.readSourceEvents(snapshot.sourceEventsJson())) {
            if (source.milestone() == TransportStatus.ARRIVED_POD
                    && source.eventTimeType() == EventTimeType.ACTUAL
                    && source.occurredAt() != null) {
                return arrivalLocalDate(source).toString();
            }
        }
        return null;
    }

    private LocalDate arrivalLocalDate(SourceEvent source) {
        if (source.timezone() != null) {
            try {
                return source.occurredAt().atZoneSameInstant(ZoneId.of(source.timezone()))
                        .toLocalDate();
            } catch (RuntimeException ignored) {
                // Unknown zone: fall back to the offset carried by the timestamp.
            }
        }
        return source.occurredAt().toLocalDate();
    }

    /** Only when both ends are present, so a half-known route is omitted deterministically. */
    private String route(ContainerRecord container) {
        if (container == null || container.pol() == null || container.pol().isBlank()
                || container.pod() == null || container.pod().isBlank()) {
            return null;
        }
        return "航线：" + container.pol() + " → " + container.pod();
    }

    /**
     * §7.1 delta with the same rule as {@link DiffSupport}: two DATETIME ETAs
     * are compared as UTC hours (so 12-07 23:30 → 12-09 00:30 is 25 hours, not
     * 2 days); a DATE on either side falls back to whole local days.
     */
    private String lastChange(Map<String, Object> oldValue, Map<String, Object> newValue) {
        if (oldValue == null || newValue == null) {
            return null;
        }
        Long hours = hoursBetween(oldValue, newValue);
        if (hours != null) {
            if (hours == 0) {
                return null;
            }
            return hours > 0 ? "较上次推迟 " + hours + " 小时"
                    : "较上次提前 " + (-hours) + " 小时";
        }
        Long days = daysBetween(dateOf(oldValue), dateOf(newValue));
        if (days == null || days == 0) {
            return null;
        }
        return days > 0 ? "较上次推迟 " + days + " 天" : "较上次提前 " + (-days) + " 天";
    }

    private Long hoursBetween(Map<String, Object> oldValue, Map<String, Object> newValue) {
        if (!"DATETIME".equals(text(oldValue, "precision"))
                || !"DATETIME".equals(text(newValue, "precision"))) {
            return null;
        }
        java.time.Instant from = instantOf(oldValue);
        java.time.Instant to = instantOf(newValue);
        if (from == null || to == null) {
            return null;
        }
        return Duration.between(from, to).toHours();
    }

    /**
     * §7.1/§3.3: a DATETIME ETA already carries the resolved instant in
     * {@code utc_at}, so comparing that avoids the DST ambiguity of turning a
     * local wall clock back into UTC (a repeated hour has two valid offsets).
     * The date/time/timezone reconstruction is used only when {@code utc_at} is
     * absent; a present-but-unparseable {@code utc_at} yields no hours and the
     * caller falls back to whole natural days instead of guessing an offset.
     */
    private java.time.Instant instantOf(Map<String, Object> value) {
        String utcAt = text(value, "utc_at");
        if (utcAt != null && !utcAt.isBlank()) {
            try {
                return java.time.Instant.parse(utcAt);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        String date = text(value, "date");
        String time = text(value, "time");
        String zone = text(value, "timezone");
        if (date == null || time == null || zone == null) {
            return null;
        }
        try {
            return ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZoneId.of(zone))
                    .toInstant();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private Long daysBetween(String from, String to) {
        if (from == null || to == null) {
            return null;
        }
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

    /**
     * §7.5 step ③: a DATETIME ETA keeps its {@code HH:MM} in the full message;
     * only the date-compression stage drops it. A DATE ETA never gains a
     * fabricated {@code 00:00}.
     */
    private String etaDisplay(Map<String, Object> value) {
        String date = dateOf(value);
        if (date == null || !"DATETIME".equals(text(value, "precision"))) {
            return date;
        }
        String time = text(value, "time");
        return time == null || time.isBlank() ? date : date + " " + time;
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
