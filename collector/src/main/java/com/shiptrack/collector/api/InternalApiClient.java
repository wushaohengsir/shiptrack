package com.shiptrack.collector.api;

import com.shiptrack.contract.internal.TrackingResultReport;
import com.shiptrack.contract.internal.TrackingResultResponse;
import com.shiptrack.contract.internal.TrackingRunDto;
import com.shiptrack.contract.internal.TrackingRunsResponse;
import com.shiptrack.contract.internal.TrackingTaskClaimRequest;
import com.shiptrack.contract.internal.TrackingTaskClaimResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Minimal client for the two internal endpoints. It holds no database
 * credentials and no business logic; every call carries the internal token.
 */
@Component
public class InternalApiClient implements CollectorApi {

    private final RestClient restClient;

    public InternalApiClient(CollectorProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));

        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Token", properties.getInternalToken())
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public List<TrackingRunDto> discoverClaimableRuns(int limit) {
        TrackingRunsResponse response = restClient.get()
                .uri("/internal/tracking-runs?state=claimable&limit={limit}", limit)
                .retrieve()
                .body(TrackingRunsResponse.class);
        return response == null || response.runs() == null ? List.of() : response.runs();
    }

    @Override
    public TrackingTaskClaimResponse claimTasks(String runId, int limit) {
        TrackingTaskClaimResponse response = restClient.post()
                .uri("/internal/tracking-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new TrackingTaskClaimRequest(runId, limit))
                .retrieve()
                .body(TrackingTaskClaimResponse.class);
        if (response == null) {
            throw new IllegalStateException("empty claim response for run " + runId);
        }
        return response;
    }

    @Override
    public TrackingResultResponse report(TrackingResultReport report) {
        return restClient.post()
                .uri("/internal/tracking-results")
                .contentType(MediaType.APPLICATION_JSON)
                .body(report)
                .retrieve()
                .body(TrackingResultResponse.class);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8080";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
