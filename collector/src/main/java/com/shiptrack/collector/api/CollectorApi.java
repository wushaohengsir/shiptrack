package com.shiptrack.collector.api;

import com.shiptrack.contract.internal.TrackingResultReport;
import com.shiptrack.contract.internal.TrackingResultResponse;
import com.shiptrack.contract.internal.TrackingRunDto;
import com.shiptrack.contract.internal.TrackingTaskClaimResponse;

import java.util.List;

/**
 * The three internal endpoints the collector depends on. Keeping it an
 * interface makes the task loop testable without an HTTP server and keeps the
 * collector free of database credentials.
 */
public interface CollectorApi {

    List<TrackingRunDto> discoverClaimableRuns(int limit);

    TrackingTaskClaimResponse claimTasks(String runId, int limit);

    TrackingResultResponse report(TrackingResultReport report);
}
