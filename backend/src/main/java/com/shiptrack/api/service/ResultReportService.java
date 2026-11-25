package com.shiptrack.api.service;

import com.shiptrack.api.config.ApiProperties;
import com.shiptrack.api.error.ApiException;
import com.shiptrack.api.repo.ContainerRecord;
import com.shiptrack.api.repo.ContainerRepository;
import com.shiptrack.api.repo.EventRecord;
import com.shiptrack.api.repo.EventRepository;
import com.shiptrack.api.repo.SnapshotRecord;
import com.shiptrack.api.repo.SnapshotRepository;
import com.shiptrack.api.support.JsonStore;
import com.shiptrack.api.support.Utc;
import com.shiptrack.contract.dto.Eta;
import com.shiptrack.contract.enums.ErrorCode;
import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.EventTimeType;
import com.shiptrack.contract.enums.EventType;
import com.shiptrack.contract.enums.TrackingHealth;
import com.shiptrack.contract.enums.TransportStatus;
import com.shiptrack.contract.internal.EventRef;
import com.shiptrack.contract.internal.OrderNotificationResult;
import com.shiptrack.contract.internal.TrackingResultReport;
import com.shiptrack.contract.internal.TrackingResultResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code POST /internal/tracking-results} (§5.23): the only writer of a
 * snapshot result, plus event generation (§8.4), health transitions (§8.5)
 * and order aggregation (§8.6).
 */
@Service
public class ResultReportService {

    private final ContainerRepository containers;
    private final SnapshotRepository snapshots;
    private final EventRepository events;
    private final AggregationService aggregation;
    private final AppConfigService config;
    private final ApiProperties properties;
    private final JsonStore jsonStore;

    public ResultReportService(ContainerRepository containers, SnapshotRepository snapshots,
                               EventRepository events, AggregationService aggregation,
                               AppConfigService config, ApiProperties properties,
                               JsonStore jsonStore) {
        this.containers = containers;
        this.snapshots = snapshots;
        this.events = events;
        this.aggregation = aggregation;
        this.config = config;
        this.properties = properties;
        this.jsonStore = jsonStore;
    }

    @Transactional
    public TrackingResultResponse report(TrackingResultReport report) {
        AppConfigService.AppConfigValues values = config.values();
        validate(report, values);

        ContainerRecord container = containers.findById(report.containerId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "箱号不存在：" + report.containerId()));
        if (report.containerNo() != null
                && !container.containerNo().equalsIgnoreCase(report.containerNo().trim())) {
            throw new ApiException(ErrorCode.CONFLICT, "container_no 与记录不一致");
        }
        if (report.carrier() != null
                && !container.carrier().equalsIgnoreCase(report.carrier().trim())) {
            throw new ApiException(ErrorCode.CONFLICT, "carrier 与记录不一致");
        }
        if (!snapshots.runExists(report.runId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "批次不存在：" + report.runId());
        }

        // §5.23 lock order: containers row, then the attempt row.
        ContainerRecord locked = containers.findByIdForUpdate(container.id())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "箱号不存在"));
        SnapshotRecord attempt = snapshots.findByRunAndContainerForUpdate(
                        report.runId(), report.containerId())
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_IN_RUN,
                        "该箱号不在本批次预建任务中"));

        if (isTerminal(attempt)) {
            if (sameContent(attempt, report)) {
                return new TrackingResultResponse(
                        attempt.id(), attempt.attemptStatus(),
                        events.findByToSnapshot(attempt.id()).stream()
                                .map(event -> new EventRef(event.id(), event.eventType()))
                                .toList(),
                        new OrderNotificationResult(
                                com.shiptrack.contract.enums.OrderNotificationState.NOT_APPLICABLE,
                                null, null));
            }
            throw new ApiException(ErrorCode.CONFLICT, "终态记录不可改写");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean leaseValid = attempt.claimToken() != null
                && attempt.claimToken().equals(report.claimToken())
                && report.runId().equals(locked.lockRunId())
                && locked.lockAcquiredAt() != null
                && locked.lockAcquiredAt()
                        .plus(Duration.ofMillis(properties.getTaskClaimTimeoutMs()))
                        .isAfter(now);
        if (!leaseValid) {
            throw new ApiException(ErrorCode.STALE_CLAIM, "领取凭据已失效，请重新领取");
        }

        List<EventRecord> generated = new ArrayList<>();
        OffsetDateTime detectedAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (Boolean.TRUE.equals(report.success())) {
            generated.addAll(applySuccess(locked, attempt, report, values, detectedAt));
        } else {
            generated.addAll(applyFailure(locked, attempt, report, values, detectedAt));
        }
        containers.releaseLock(container.id(), report.runId());

        OrderNotificationResult notification = aggregation.aggregate(
                locked.orderId(), report.runId());
        return new TrackingResultResponse(
                attempt.id(),
                Boolean.TRUE.equals(report.success())
                        ? com.shiptrack.contract.enums.AttemptStatus.SUCCESS
                        : com.shiptrack.contract.enums.AttemptStatus.FAILED,
                generated.stream().map(event -> new EventRef(event.id(), event.eventType())).toList(),
                notification);
    }

    // ------------------------------------------------------------------
    // Success
    // ------------------------------------------------------------------

    private List<EventRecord> applySuccess(ContainerRecord container, SnapshotRecord attempt,
                                           TrackingResultReport report,
                                           AppConfigService.AppConfigValues values,
                                           OffsetDateTime detectedAt) {
        TransportStatus status = report.transportStatus() == null
                ? TransportStatus.UNKNOWN : report.transportStatus();
        List<Eta> etas = report.etas() == null ? List.of() : report.etas();
        String etasJson = jsonStore.etas(etas);
        String sourceEventsJson = jsonStore.sourceEvents(report.sourceEvents() == null
                ? List.of() : report.sourceEvents());
        String rawPayloadJson = report.rawPayload() == null
                ? null : jsonStore.write(report.rawPayload());
        String contentHash = DiffSupport.contentHash(report);
        Timestamp finishedAt = Utc.timestamp(detectedAt);

        snapshots.completeSuccess(attempt.id(), finishedAt,
                report.retryCount(), report.source(), status.name(), report.rawStatus(),
                etasJson, sourceEventsJson, report.location(), report.vessel(), report.voyage(),
                report.pol(), report.pod(), report.finalDestination(), contentHash,
                report.mappingVersion(), rawPayloadJson);

        Eta currentEta = DiffSupport.etaForMilestone(etas, container);
        SnapshotRecord previous = snapshots.previousSuccess(container.id(), attempt.id())
                .orElse(null);
        List<Eta> previousEtas = previous == null ? List.of() : parseEtas(previous.etasJson());
        Eta previousEta = DiffSupport.etaForMilestone(previousEtas, container);
        Eta baselineEta = baselineEta(container);
        EtaRisk riskAfter = DiffSupport.riskAgainstBaseline(baselineEta, currentEta, values);

        List<EventRecord> generated = new ArrayList<>();
        boolean firstBaseline = container.baselineSnapshotId() == null;
        boolean contentChanged = previous == null
                || !Objects.equals(previous.contentHash(), contentHash);

        if (!firstBaseline && contentChanged) {
            if (DiffSupport.etaChanged(previousEta, currentEta, values)) {
                EventType type = container.trackingHealth() != TrackingHealth.OK
                        ? EventType.ETA_RECOVERED : EventType.ETA_CHANGED;
                generated.add(events.insertOrGet(
                        container.id(),
                        DiffSupport.etaEventKey(container.id(), type.name(),
                                previous == null ? null : previous.id(), attempt.id()),
                        type, "eta", EventTimeType.PLANNED,
                        previous == null ? null : previous.id(), attempt.id(),
                        container.baselineSnapshotId(), jsonStore.write(DiffSupport.baselineDelta(baselineEta, currentEta)),
                        container.etaRisk(), riskAfter,
                        jsonStore.write(DiffSupport.etaValue(previousEta)), jsonStore.write(DiffSupport.etaValue(currentEta)),
                        detectedAt, report.source()));
            }
            TransportStatus previousStatus = previous == null ? null : previous.transportStatus();
            if (previousStatus != null && previousStatus != TransportStatus.UNKNOWN
                    && status != TransportStatus.UNKNOWN && previousStatus != status) {
                boolean corrected = status.ordinal() < previousStatus.ordinal()
                        || looksAmended(report.rawStatus());
                EventType type = corrected ? EventType.STATUS_CORRECTED : EventType.STATUS_CHANGED;
                generated.add(events.insertOrGet(
                        container.id(),
                        DiffSupport.statusEventKey(container.id(), type.name(),
                                previous == null ? null : previous.id(), attempt.id()),
                        type, "transport_status", null,
                        previous == null ? null : previous.id(), attempt.id(),
                        null, null, container.etaRisk(), riskAfter,
                        jsonStore.write(DiffSupport.statusValue(previousStatus)),
                        jsonStore.write(DiffSupport.statusValue(status)),
                        detectedAt, report.source()));
            }
        }
        if (container.trackingHealth() != TrackingHealth.OK) {
            generated.add(events.insertOrGet(
                    container.id(),
                    DiffSupport.healthEventKey(container.id(), EventType.QUERY_RECOVERED.name(),
                            previous == null ? null : previous.id(), attempt.id()),
                    EventType.QUERY_RECOVERED, null, null,
                    previous == null ? null : previous.id(), attempt.id(),
                    null, null, container.etaRisk(), riskAfter, null, null,
                    detectedAt, report.source()));
        }

        containers.updateAfterSuccess(container.id(), detectedAt, status.name(), riskAfter,
                currentEta == null ? null : currentEta.rawValue(),
                currentEta == null ? null : currentEta.date(),
                currentEta == null ? null : currentEta.time(),
                currentEta == null ? null : currentEta.timezone(),
                currentEta == null ? null : currentEta.precision(),
                currentEta == null || currentEta.utcAt() == null ? null
                        : currentEta.utcAt().atOffset(ZoneOffset.UTC),
                report.pol(), report.pod(), report.finalDestination(), report.location(),
                report.vessel(), report.voyage(),
                firstBaseline ? attempt.id() : container.baselineSnapshotId(),
                detectedAt);
        return generated;
    }

    // ------------------------------------------------------------------
    // Failure
    // ------------------------------------------------------------------

    private List<EventRecord> applyFailure(ContainerRecord container, SnapshotRecord attempt,
                                           TrackingResultReport report,
                                           AppConfigService.AppConfigValues values,
                                           OffsetDateTime detectedAt) {
        String errorCode = report.errorCode();
        snapshots.completeFailure(attempt.id(), Utc.timestamp(detectedAt),
                report.retryCount(), report.source(), errorCode, report.errorMessage(),
                report.mappingVersion(),
                report.rawPayload() == null ? null : jsonStore.write(report.rawPayload()));

        OffsetDateTime reference = container.lastSuccessAt() != null
                ? container.lastSuccessAt()
                : container.trackingStartedAt() != null ? container.trackingStartedAt() : detectedAt;
        boolean stale = !reference.plusHours(values.staleAfterHours()).isAfter(detectedAt);
        TrackingHealth newHealth;
        if (stale) {
            newHealth = TrackingHealth.STALE;
        } else if ("PARSE_ERROR".equalsIgnoreCase(errorCode)) {
            newHealth = TrackingHealth.PARSE_ERROR;
        } else {
            newHealth = TrackingHealth.QUERY_FAILED;
        }

        SnapshotRecord previous = snapshots.previousSuccess(container.id(), attempt.id()).orElse(null);
        Long fromSnapshotId = previous == null ? null : previous.id();
        List<EventRecord> generated = new ArrayList<>();

        if (container.trackingHealth() == TrackingHealth.OK) {
            generated.add(events.insertOrGet(
                    container.id(),
                    DiffSupport.healthEventKey(container.id(), EventType.QUERY_FAILED.name(),
                            fromSnapshotId, attempt.id()),
                    EventType.QUERY_FAILED, null, null,
                    fromSnapshotId, attempt.id(), null, null, null, null,
                    null, null, detectedAt, report.source()));
        }
        if (stale && container.trackingHealth() != TrackingHealth.STALE) {
            generated.add(events.insertOrGet(
                    container.id(),
                    DiffSupport.healthEventKey(container.id(), EventType.DATA_STALE.name(),
                            fromSnapshotId, attempt.id()),
                    EventType.DATA_STALE, null, null,
                    fromSnapshotId, attempt.id(), null, null, null, null,
                    null, null, detectedAt, report.source()));
        }

        containers.updateAfterFailure(container.id(), detectedAt, errorCode, newHealth);
        return generated;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void validate(TrackingResultReport report, AppConfigService.AppConfigValues values) {
        if (report == null || report.runId() == null || report.runId().isBlank()
                || report.containerId() == null || report.claimToken() == null
                || report.claimToken().isBlank() || report.retryCount() == null
                || report.mappingVersion() == null || report.queriedAt() == null
                || report.success() == null || report.source() == null
                || report.source().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "run_id/container_id/claim_token/retry_count/mapping_version/queried_at/"
                            + "success/source 必填");
        }
        if (report.retryCount() < 0 || report.retryCount() > values.maxRetryPerRun()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "retry_count 必须在 0.." + values.maxRetryPerRun());
        }
        if (report.mappingVersion() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "mapping_version 必须为正整数");
        }
        if (Boolean.TRUE.equals(report.success())
                && (report.transportStatus() == null || report.source() == null)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "成功上报必须携带 transport_status");
        }
        if (!Boolean.TRUE.equals(report.success())
                && (report.errorCode() == null || report.errorCode().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "失败上报必须携带 error_code");
        }
    }

    private boolean isTerminal(SnapshotRecord attempt) {
        return attempt.attemptStatus() == com.shiptrack.contract.enums.AttemptStatus.SUCCESS
                || attempt.attemptStatus() == com.shiptrack.contract.enums.AttemptStatus.FAILED
                || attempt.attemptStatus() == com.shiptrack.contract.enums.AttemptStatus.SKIPPED;
    }

    /** Idempotent retry (§5.23 step 7). */
    private boolean sameContent(SnapshotRecord stored, TrackingResultReport report) {
        if (!Objects.equals(stored.retryCount(), report.retryCount())
                || !Objects.equals(stored.success(), report.success())
                || !Objects.equals(stored.transportStatus(), report.transportStatus())
                || !Objects.equals(stored.rawStatus(), report.rawStatus())
                || !Objects.equals(stored.location(), report.location())
                || !Objects.equals(stored.vessel(), report.vessel())
                || !Objects.equals(stored.voyage(), report.voyage())
                || !Objects.equals(stored.pol(), report.pol())
                || !Objects.equals(stored.pod(), report.pod())
                || !Objects.equals(stored.finalDestination(), report.finalDestination())
                || !Objects.equals(stored.errorCode(), report.errorCode())
                || !Objects.equals(stored.errorMessage(), report.errorMessage())) {
            return false;
        }
        if (Boolean.TRUE.equals(report.success())) {
            // MySQL normalises JSON columns, so compare the re-serialised form.
            return Objects.equals(jsonStore.etas(jsonStore.readEtas(stored.etasJson())),
                    jsonStore.etas(report.etas() == null ? List.of() : report.etas()))
                    && Objects.equals(
                    jsonStore.sourceEvents(jsonStore.readSourceEvents(stored.sourceEventsJson())),
                    jsonStore.sourceEvents(report.sourceEvents() == null
                            ? List.of() : report.sourceEvents()));
        }
        return true;
    }

    private Eta baselineEta(ContainerRecord container) {
        if (container.baselineSnapshotId() == null) {
            return null;
        }
        return snapshots.findById(container.baselineSnapshotId())
                .map(snapshot -> DiffSupport.etaForMilestone(parseEtas(snapshot.etasJson()), container))
                .orElse(null);
    }

    private List<Eta> parseEtas(String json) {
        return jsonStore.readEtas(json);
    }

    private boolean looksAmended(String rawStatus) {
        if (rawStatus == null) {
            return false;
        }
        String text = rawStatus.toLowerCase(Locale.ROOT);
        return text.contains("cancel") || text.contains("amend");
    }
}
