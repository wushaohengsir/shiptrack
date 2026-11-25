package com.shiptrack.api.service;

import com.shiptrack.api.error.ApiException;
import com.shiptrack.api.repo.ContainerRecord;
import com.shiptrack.api.repo.ContainerRepository;
import com.shiptrack.api.repo.EventRecord;
import com.shiptrack.api.repo.EventRepository;
import com.shiptrack.api.repo.NotificationRecord;
import com.shiptrack.api.repo.NotificationRepository;
import com.shiptrack.api.repo.SnapshotRecord;
import com.shiptrack.api.repo.SnapshotRepository;
import com.shiptrack.contract.dto.Eta;
import com.shiptrack.contract.dto.SourceEvent;
import com.shiptrack.contract.enums.ErrorCode;
import com.shiptrack.api.support.JsonStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code GET /containers/{id}/timeline} (§5.12). The three sources are merged
 * in SQL so pagination is stable on
 * {@code timeline_at DESC, type_rank DESC, id DESC}.
 */
@Service
public class TimelineService {

    private static final Set<String> ALL_TYPES = Set.of("snapshot", "event", "notification");

    private final JdbcTemplate jdbc;
    private final ContainerRepository containers;
    private final SnapshotRepository snapshots;
    private final EventRepository events;
    private final NotificationRepository notifications;
    private final JsonStore jsonStore;

    public TimelineService(JdbcTemplate jdbc, ContainerRepository containers,
                           SnapshotRepository snapshots, EventRepository events,
                           NotificationRepository notifications, JsonStore jsonStore) {
        this.jdbc = jdbc;
        this.containers = containers;
        this.snapshots = snapshots;
        this.events = events;
        this.notifications = notifications;
        this.jsonStore = jsonStore;
    }

    public Map<String, Object> timeline(long containerId, Set<String> requestedTypes,
                                        int page, int pageSize) {
        containers.findById(containerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "箱号不存在：" + containerId));
        Set<String> types = requestedTypes == null || requestedTypes.isEmpty()
                ? ALL_TYPES : requestedTypes;
        String in = String.join(",", types.stream().map(type -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(containerId);
        args.add(containerId);
        args.add(containerId);
        List<Object> typeArgs = new ArrayList<>(types);

        String union = """
                SELECT 'event' AS type, 2 AS type_rank, e.id AS id, e.detected_at AS timeline_at
                    FROM tracking_events e WHERE e.container_id = ?
                UNION ALL
                SELECT 'snapshot' AS type, 1 AS type_rank, s.id AS id,
                       COALESCE(s.finished_at, s.started_at, s.queued_at) AS timeline_at
                    FROM tracking_snapshots s WHERE s.container_id = ?
                UNION ALL
                SELECT 'notification' AS type, 3 AS type_rank, n.id AS id, n.created_at AS timeline_at
                    FROM notifications n
                    WHERE n.id IN (SELECT notification_id FROM tracking_events
                                   WHERE container_id = ? AND notification_id IS NOT NULL)
                """;

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + union + ") t WHERE t.type IN ("
                        + in + ")",
                Long.class, concat(args, typeArgs).toArray());
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        int offset = (safePage - 1) * safeSize;
        List<Object> pageArgs = new ArrayList<>(concat(args, typeArgs));
        pageArgs.add(safeSize);
        pageArgs.add(offset);
        List<TimelineKey> keys = jdbc.query("SELECT t.type, t.type_rank, t.id, t.timeline_at FROM ("
                        + union + ") t WHERE t.type IN (" + in + ") "
                        + "ORDER BY t.timeline_at DESC, t.type_rank DESC, t.id DESC LIMIT ? OFFSET ?",
                (resultSet, index) -> new TimelineKey(
                        resultSet.getString("type"),
                        resultSet.getInt("type_rank"),
                        resultSet.getLong("id"),
                        com.shiptrack.api.support.Utc.read(resultSet, "timeline_at")),
                pageArgs.toArray());

        List<Map<String, Object>> items = new ArrayList<>(keys.size());
        for (TimelineKey key : keys) {
            items.add(render(key));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("container_id", containerId);
        response.put("items", items);
        response.put("page", safePage);
        response.put("page_size", safeSize);
        response.put("total", total == null ? 0 : total);
        return response;
    }

    private Map<String, Object> render(TimelineKey key) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", key.type());
        item.put("type_rank", key.typeRank());
        item.put("id", key.id());
        item.put("timeline_at", key.timelineAt());
        switch (key.type()) {
            case "snapshot" -> snapshotItem(item, key.id());
            case "event" -> eventItem(item, key.id());
            case "notification" -> notificationItem(item, key.id());
            default -> {
            }
        }
        return item;
    }

    private void snapshotItem(Map<String, Object> item, long id) {
        SnapshotRecord snapshot = snapshots.findById(id).orElse(null);
        if (snapshot == null) {
            return;
        }
        item.put("container_id", snapshot.containerId());
        item.put("run_id", snapshot.runId());
        item.put("attempt_status", snapshot.attemptStatus());
        item.put("queried_at", snapshot.finishedAt());
        item.put("transport_status", snapshot.transportStatus());
        item.put("raw_status", snapshot.rawStatus());
        item.put("location", snapshot.location());
        item.put("vessel", snapshot.vessel());
        item.put("voyage", snapshot.voyage());
        item.put("etas", parseEtas(snapshot.etasJson()));
        item.put("source_events", parseSourceEvents(snapshot.sourceEventsJson()));
        item.put("error_code", snapshot.errorCode());
        item.put("error_message", snapshot.errorMessage());
    }

    private void eventItem(Map<String, Object> item, long id) {
        EventRecord event = events.findById(id).orElse(null);
        if (event == null) {
            return;
        }
        item.put("event_type", event.eventType());
        item.put("field_name", event.fieldName());
        item.put("event_time_type", event.eventTimeType());
        item.put("old_value", parseMap(event.oldValueJson()));
        item.put("new_value", parseMap(event.newValueJson()));
        item.put("from_snapshot_id", event.fromSnapshotId());
        item.put("to_snapshot_id", event.toSnapshotId());
        item.put("notification_id", event.notificationId());
        item.put("detected_at", event.detectedAt());
    }

    private void notificationItem(Map<String, Object> item, long id) {
        NotificationRecord notification = notifications.findById(id).orElse(null);
        if (notification == null) {
            return;
        }
        item.put("run_id", notification.runId());
        item.put("status", notification.status());
        item.put("created_at", notification.createdAt());
        item.put("sent_at", notification.sentAt());
    }

    private List<Eta> parseEtas(String json) {
        return jsonStore.readEtas(json);
    }

    private List<SourceEvent> parseSourceEvents(String json) {
        return jsonStore.readSourceEvents(json);
    }

    private Map<String, Object> parseMap(String json) {
        return jsonStore.readMap(json);
    }

    private List<Object> concat(List<Object> first, List<Object> second) {
        List<Object> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    public static Set<String> parseTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL_TYPES;
        }
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim().toLowerCase();
            if (ALL_TYPES.contains(value)) {
                result.add(value);
            }
        }
        return result.isEmpty() ? ALL_TYPES : result;
    }

    private record TimelineKey(String type, int typeRank, long id, OffsetDateTime timelineAt) {
    }
}
