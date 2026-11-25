package com.shiptrack.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feishu delivery settings (§7). The webhook URL and signature secret are bound
 * from {@code FEISHU_WEBHOOK_URL} / {@code FEISHU_WEBHOOK_SECRET} in
 * {@code application.yaml}; they must never live in the repository or in
 * {@code config.yaml}.
 */
@ConfigurationProperties(prefix = "app.feishu")
public class FeishuProperties {

    private String webhookUrl = "";
    private String webhookSecret = "";
    private boolean dispatchEnabled = true;
    private long requestTimeoutMs = 5_000L;
    private long connectTimeoutMs = 3_000L;
    private long sendingTimeoutMs = 60_000L;
    private long firstRetryBackoffMs = 60_000L;
    private long secondRetryBackoffMs = 300_000L;
    private int maxPerTick = 5;
    private int ratePerSecond = 5;
    private int ratePerMinute = 100;

    /** A URL alone is enough to send; the signature is only added when a secret is set. */
    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    public boolean hasSecret() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
    }

    public boolean isDispatchEnabled() {
        return dispatchEnabled;
    }

    public void setDispatchEnabled(boolean dispatchEnabled) {
        this.dispatchEnabled = dispatchEnabled;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getSendingTimeoutMs() {
        return sendingTimeoutMs;
    }

    public void setSendingTimeoutMs(long sendingTimeoutMs) {
        this.sendingTimeoutMs = sendingTimeoutMs;
    }

    public long getFirstRetryBackoffMs() {
        return firstRetryBackoffMs;
    }

    public void setFirstRetryBackoffMs(long firstRetryBackoffMs) {
        this.firstRetryBackoffMs = firstRetryBackoffMs;
    }

    public long getSecondRetryBackoffMs() {
        return secondRetryBackoffMs;
    }

    public void setSecondRetryBackoffMs(long secondRetryBackoffMs) {
        this.secondRetryBackoffMs = secondRetryBackoffMs;
    }

    public int getMaxPerTick() {
        return maxPerTick;
    }

    public void setMaxPerTick(int maxPerTick) {
        this.maxPerTick = maxPerTick;
    }

    public int getRatePerSecond() {
        return ratePerSecond;
    }

    public void setRatePerSecond(int ratePerSecond) {
        this.ratePerSecond = ratePerSecond;
    }

    public int getRatePerMinute() {
        return ratePerMinute;
    }

    public void setRatePerMinute(int ratePerMinute) {
        this.ratePerMinute = ratePerMinute;
    }
}
