package com.shiptrack.collector.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings of the task loop. All values are env-overridable; the
 * collector never reads the database and knows only the internal API.
 */
@ConfigurationProperties(prefix = "collector")
public class CollectorProperties {

    /** Base URL of the ship-tracking API, e.g. http://backend:8080. */
    private String baseUrl = "http://localhost:8080";

    /** Shared secret for /internal/*; an empty value disables the loop. */
    private String internalToken = "";

    private int pollIntervalMs = 30_000;
    /** Tasks handled in one cycle; each is claimed with {@code limit:1} (§3.2). */
    private int maxTasksPerPoll = 100;
    /** Runs requested per discovery call; the API caps this at 50 (§5.24). */
    private int maxRunsPerPoll = 20;
    private long retryBackoffMs = 2_000;
    /** Bounded retries for a report that failed with a transient error (§11). */
    private int reportMaxAttempts = 3;
    private long reportBackoffMs = 1_000;
    private long emptyRunBackoffMs = 10_000;
    private long emptyClaimBackoffMs = 5_000;
    private String queryZone = "Asia/Shanghai";
    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs = 30_000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public int getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(int pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getMaxTasksPerPoll() {
        return maxTasksPerPoll;
    }

    public void setMaxTasksPerPoll(int maxTasksPerPoll) {
        this.maxTasksPerPoll = maxTasksPerPoll;
    }

    public int getMaxRunsPerPoll() {
        return maxRunsPerPoll;
    }

    public void setMaxRunsPerPoll(int maxRunsPerPoll) {
        this.maxRunsPerPoll = maxRunsPerPoll;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public int getReportMaxAttempts() {
        return reportMaxAttempts;
    }

    public void setReportMaxAttempts(int reportMaxAttempts) {
        this.reportMaxAttempts = reportMaxAttempts;
    }

    public long getReportBackoffMs() {
        return reportBackoffMs;
    }

    public void setReportBackoffMs(long reportBackoffMs) {
        this.reportBackoffMs = reportBackoffMs;
    }

    public long getEmptyRunBackoffMs() {
        return emptyRunBackoffMs;
    }

    public void setEmptyRunBackoffMs(long emptyRunBackoffMs) {
        this.emptyRunBackoffMs = emptyRunBackoffMs;
    }

    public long getEmptyClaimBackoffMs() {
        return emptyClaimBackoffMs;
    }

    public void setEmptyClaimBackoffMs(long emptyClaimBackoffMs) {
        this.emptyClaimBackoffMs = emptyClaimBackoffMs;
    }

    public String getQueryZone() {
        return queryZone;
    }

    public void setQueryZone(String queryZone) {
        this.queryZone = queryZone;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
