package com.shiptrack.collector.api;

import com.shiptrack.collector.mapping.StatusMappingSet;
import com.shiptrack.collector.msc.MscPageSnapshot;
import com.shiptrack.collector.msc.ParsedTracking;
import com.shiptrack.collector.msc.RawEta;
import com.shiptrack.collector.msc.RawEvent;
import com.shiptrack.collector.msc.StatusWording;
import com.shiptrack.collector.msc.TrackingOutcome;
import com.shiptrack.contract.dto.Eta;
import com.shiptrack.contract.dto.SourceEvent;
import com.shiptrack.contract.enums.EtaPrecision;
import com.shiptrack.contract.enums.TransportStatus;
import com.shiptrack.contract.internal.ClaimedTaskDto;
import com.shiptrack.contract.internal.TrackingResultReport;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an attempt into the exact report body of §5.23. The mapping snapshot is
 * fixed by the caller at claim time, so {@code mapping_version} always describes
 * the rules that actually ran.
 */
@Component
public class TrackingResultFactory {

    private static final String SOURCE = "msc_web";
    private static final int MAX_ERROR_MESSAGE = 1_000;

    public TrackingResultReport build(String runId,
                                      ClaimedTaskDto task,
                                      StatusMappingSet mappings,
                                      TrackingOutcome outcome,
                                      int retryCount,
                                      OffsetDateTime queriedAt) {
        if (outcome instanceof TrackingOutcome.Success success) {
            return successReport(runId, task, mappings, success, retryCount, queriedAt);
        }
        TrackingOutcome.Failure failure = (TrackingOutcome.Failure) outcome;
        return failureReport(runId, task, mappings, failure, retryCount, queriedAt);
    }

    private TrackingResultReport successReport(String runId,
                                               ClaimedTaskDto task,
                                               StatusMappingSet mappings,
                                               TrackingOutcome.Success success,
                                               int retryCount,
                                               OffsetDateTime queriedAt) {
        ParsedTracking parsed = success.parsed();
        String carrier = task.carrier();
        // A plan and a cancellation both describe something that is not an
        // actual state. Using the same wording rules as the timeline is what
        // keeps "Container picked up cancelled" out of the terminal PICKED_UP
        // status (AC-29) while its event is still reported as CANCELLED.
        TransportStatus status = StatusWording.isPlanned(parsed.rawStatus())
                || StatusWording.isCancelled(parsed.rawStatus())
                ? TransportStatus.UNKNOWN
                : mappings.normalize(carrier, parsed.rawStatus());

        List<Eta> etas = new ArrayList<>();
        for (RawEta raw : parsed.etas()) {
            Eta eta = toEta(raw);
            if (eta != null) {
                etas.add(eta);
            }
        }

        List<SourceEvent> events = new ArrayList<>();
        for (RawEvent raw : parsed.events()) {
            events.add(toEvent(carrier, mappings, raw));
        }

        return new TrackingResultReport(
                runId,
                task.containerId(),
                task.containerNo(),
                carrier,
                task.claimToken(),
                retryCount,
                mappings.version(),
                queriedAt,
                true,
                null,
                null,
                parsed.rawStatus(),
                status,
                etas,
                events,
                parsed.location(),
                parsed.vessel(),
                parsed.voyage(),
                parsed.pol(),
                parsed.pod(),
                parsed.finalDestination(),
                SOURCE,
                rawPayload(success.page()));
    }

    private TrackingResultReport failureReport(String runId,
                                               ClaimedTaskDto task,
                                               StatusMappingSet mappings,
                                               TrackingOutcome.Failure failure,
                                               int retryCount,
                                               OffsetDateTime queriedAt) {
        String message = failure.message();
        if (message != null && message.length() > MAX_ERROR_MESSAGE) {
            message = message.substring(0, MAX_ERROR_MESSAGE);
        }
        return new TrackingResultReport(
                runId,
                task.containerId(),
                task.containerNo(),
                task.carrier(),
                task.claimToken(),
                retryCount,
                mappings.version(),
                queriedAt,
                false,
                failure.errorCode().name(),
                message,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                SOURCE,
                null);
    }

    private static Eta toEta(RawEta raw) {
        if (raw.date() == null) {
            return null;
        }
        if (raw.time() != null && raw.timezone() != null) {
            try {
                ZoneId zone = ZoneId.of(raw.timezone());
                if (zone instanceof ZoneOffset) {
                    // §3.3 wants an IANA zone name. A half-hour offset such as
                    // +05:30 has no fixed-offset IANA name, so the value is
                    // downgraded to DATE instead of publishing a non-IANA id.
                    return dateOnlyEta(raw);
                }
                return new Eta(
                        raw.milestone(),
                        raw.rawValue(),
                        raw.date(),
                        raw.time(),
                        raw.timezone(),
                        EtaPrecision.DATETIME,
                        raw.date().atTime(raw.time()).atZone(zone).toInstant());
            } catch (RuntimeException e) {
                // Unknown zone: keep the date-only value instead of guessing.
            }
        }
        return dateOnlyEta(raw);
    }

    private static Eta dateOnlyEta(RawEta raw) {
        return new Eta(
                raw.milestone(),
                raw.rawValue(),
                raw.date(),
                null,
                null,
                EtaPrecision.DATE,
                null);
    }

    private static SourceEvent toEvent(String carrier, StatusMappingSet mappings, RawEvent raw) {
        String text = raw.rawText();
        TransportStatus milestone = mappings.normalize(carrier, text);
        return new SourceEvent(
                milestone,
                raw.eventTimeType(),
                raw.occurredAt(),
                raw.timezone(),
                null,
                null,
                null,
                text);
    }

    private static Map<String, Object> rawPayload(MscPageSnapshot page) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", page.title());
        payload.put("url", page.url());
        payload.put("body_text", page.bodyText());
        return payload;
    }
}
