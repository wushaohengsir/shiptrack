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
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Minimal client for the two internal endpoints. It holds no database
 * credentials and no business logic; every call carries the internal token.
 */
@Component
public class InternalApiClient implements CollectorApi {

    private final RestClient.Builder builder;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final RestClient restClient;

    public InternalApiClient(CollectorProperties properties) {
        this.connectTimeoutMs = properties.getConnectTimeoutMs();
        this.readTimeoutMs = properties.getReadTimeoutMs();
        this.builder = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .defaultHeader("X-Internal-Token", properties.getInternalToken())
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        this.restClient = client(properties.getReadTimeoutMs());
    }

    /**
     * A report must not outlive the claim lease. {@code timeoutMs} is one
     * absolute budget for sending the body and reading the whole response:
     * connect and per-read timeouts are capped to it, and the call is also
     * bounded on a worker thread so a server that drips JSON cannot stretch the
     * total. Building a client per call is cheap compared to the carrier query
     * that produced the report.
     */
    @Override
    public TrackingResultResponse report(TrackingResultReport report, long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalStateException("no lease time left to report");
        }
        int effectiveRead = (int) Math.min(readTimeoutMs, timeoutMs);
        int effectiveConnect = (int) Math.min(connectTimeoutMs, timeoutMs);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(1, effectiveConnect)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1, effectiveRead)));
        RestClient client = builder.clone().requestFactory(requestFactory).build();

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "report-http");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<TrackingResultResponse> pending = executor.submit(() -> client.post()
                    .uri("/internal/tracking-results")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(report)
                    .retrieve()
                    .body(TrackingResultResponse.class));
            return pending.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RestClientException("report did not finish within " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestClientException("report interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RestClientException("report failed: " + cause.getMessage(), cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private RestClient client(long timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return builder.clone().requestFactory(requestFactory).build();
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
        return report(report, readTimeoutMs);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8080";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
