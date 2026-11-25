package com.shiptrack.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings of the API. Secrets only ever come from the environment.
 *
 * <p>{@code accessToken} blank means every public request is rejected with 401
 * (deny-by-default); {@code internalToken} blank means the collector endpoints
 * are unusable. Neither has a built-in default, so a missing deployment secret
 * fails closed instead of opening the API.</p>
 */
@ConfigurationProperties(prefix = "app")
public class ApiProperties {

    private String accessToken = "";
    private String internalToken = "";
    private String configPath = "config/config.yaml";
    private String statusMappingPath = "config/status-mappings.yaml";
    private boolean schedulerEnabled = false;
    private long taskClaimTimeoutMs = 900_000L;
    private String corsAllowedOrigins = "";

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken == null ? "" : accessToken;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }

    public String getStatusMappingPath() {
        return statusMappingPath;
    }

    public void setStatusMappingPath(String statusMappingPath) {
        this.statusMappingPath = statusMappingPath;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public long getTaskClaimTimeoutMs() {
        return taskClaimTimeoutMs;
    }

    public void setTaskClaimTimeoutMs(long taskClaimTimeoutMs) {
        this.taskClaimTimeoutMs = taskClaimTimeoutMs;
    }

    public String getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(String corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }
}
