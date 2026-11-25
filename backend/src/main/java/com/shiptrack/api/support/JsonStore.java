package com.shiptrack.api.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiptrack.contract.dto.Eta;
import com.shiptrack.contract.dto.SourceEvent;
import com.shiptrack.contract.enums.EtaPrecision;
import com.shiptrack.contract.enums.EventTimeType;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.enums.TransportStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON persistence for the columns the API writes itself.
 *
 * <p>HTTP bodies are handled by the Boot Jackson 3 converter; this helper only
 * needs plain maps and explicit ISO strings, so no JavaTime module is required
 * and the stored format is stable across Jackson upgrades.</p>
 */
@Component
public class JsonStore {

    private final ObjectMapper mapper = new ObjectMapper();

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialise JSON", error);
        }
    }

    public String etas(List<Eta> etas) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (etas != null) {
            for (Eta eta : etas) {
                if (eta == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("milestone", eta.milestone() == null ? null : eta.milestone().name());
                row.put("raw_value", eta.rawValue());
                row.put("date", eta.date() == null ? null : eta.date().toString());
                row.put("time", eta.time() == null ? null : eta.time().toString());
                row.put("timezone", eta.timezone());
                row.put("precision", eta.precision() == null ? null : eta.precision().name());
                row.put("utc_at", eta.utcAt() == null ? null : eta.utcAt().toString());
                rows.add(row);
            }
        }
        return write(rows);
    }

    public List<Eta> readEtas(String json) {
        List<Eta> etas = new ArrayList<>();
        for (Map<String, Object> row : readRows(json)) {
            etas.add(new Eta(
                    value(row, "milestone") == null ? null
                            : TrackedMilestone.valueOf(text(row, "milestone")),
                    value(row, "raw_value") == null ? null : text(row, "raw_value"),
                    value(row, "date") == null ? null : LocalDate.parse(text(row, "date")),
                    value(row, "time") == null ? null : LocalTime.parse(text(row, "time")),
                    value(row, "timezone") == null ? null : text(row, "timezone"),
                    value(row, "precision") == null ? null : EtaPrecision.valueOf(text(row, "precision")),
                    value(row, "utc_at") == null ? null : Instant.parse(text(row, "utc_at"))));
        }
        return etas;
    }

    public String sourceEvents(List<SourceEvent> events) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (events != null) {
            for (SourceEvent event : events) {
                if (event == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("milestone", event.milestone() == null ? null : event.milestone().name());
                row.put("event_time_type", event.eventTimeType() == null
                        ? null : event.eventTimeType().wireValue());
                row.put("occurred_at", event.occurredAt() == null
                        ? null : event.occurredAt().toString());
                row.put("timezone", event.timezone());
                row.put("location", event.location());
                row.put("vessel", event.vessel());
                row.put("voyage", event.voyage());
                row.put("raw_text", event.rawText());
                rows.add(row);
            }
        }
        return write(rows);
    }

    public List<SourceEvent> readSourceEvents(String json) {
        List<SourceEvent> events = new ArrayList<>();
        for (Map<String, Object> row : readRows(json)) {
            events.add(new SourceEvent(
                    value(row, "milestone") == null ? null
                            : TransportStatus.valueOf(text(row, "milestone")),
                    value(row, "event_time_type") == null ? null
                            : EventTimeType.fromWire(text(row, "event_time_type")),
                    value(row, "occurred_at") == null ? null
                            : OffsetDateTime.parse(text(row, "occurred_at")),
                    value(row, "timezone") == null ? null : text(row, "timezone"),
                    value(row, "location") == null ? null : text(row, "location"),
                    value(row, "vessel") == null ? null : text(row, "vessel"),
                    value(row, "voyage") == null ? null : text(row, "voyage"),
                    value(row, "raw_text") == null ? null : text(row, "raw_text")));
        }
        return events;
    }

    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException error) {
            return null;
        }
    }

    private List<Map<String, Object>> readRows(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private static Object value(Map<String, Object> row, String key) {
        return row.get(key);
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }
}
