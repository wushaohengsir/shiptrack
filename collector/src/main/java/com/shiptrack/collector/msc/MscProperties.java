package com.shiptrack.collector.msc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Site-specific settings. Selectors are deliberately configuration, not code:
 * MSC changes page structure without notice and the fix must be a config edit.
 *
 * <p><b>Status:</b> the default selectors are a first-cut probe for the public
 * tracking page and have <i>not</i> been validated against the live site yet.
 * Requirement §13.1 gates that validation; see the M2 delivery notes.</p>
 */
@ConfigurationProperties(prefix = "collector.msc")
public class MscProperties {

    private String baseUrl = "https://www.msc.com/en/track-a-shipment";

    private String containerInputSelector =
            "input[name='trackingNumber'], input#trackingNumber, "
                    + "input[placeholder*='Container'], input[placeholder*='container']";

    private String submitSelector = "button[type='submit'], button:has-text('Track')";

    private String resultSelector =
            "[data-testid='tracking-result'], .tracking-result, .tracking-details, "
                    + ".shipment-details";

    private String statusSelector =
            "[data-testid='status'], .tracking-status, .status-value, .shipment-status";

    private String eventRowSelector =
            "[data-testid='event-row'], .timeline li, .tracking-events li, table tbody tr";

    /**
     * Path pattern of the carrier's real tracking call. Only a request whose
     * path matches this, names the queried container and starts with the
     * submit can be correlated with the result. Analytics beacons that embed
     * the container number therefore never stand in for the tracking call.
     */
    private String trackingUrlPattern = "(?i)(?:track|shipment|container|cargo|search|api)";

    /** No result but one of these phrases means the container is unknown. */
    private List<String> notFoundTexts = new ArrayList<>(List.of(
            "no results found",
            "no tracking information",
            "container not found",
            "invalid container number",
            "we could not find"));

    private List<String> captchaTexts = new ArrayList<>(List.of(
            "verify you are human",
            "are you a robot",
            "captcha",
            "unusual traffic"));

    private List<String> rateLimitTexts = new ArrayList<>(List.of(
            "too many requests",
            "rate limit",
            "try again later"));

    /** If a password box is visible the site forced a login. */
    private String loginFieldSelector = "input[type='password']";

    private int resultWaitMs = 30_000;
    /** Hard per-query budget; §3.2 requires 30 s and the cap below enforces it. */
    private int queryTimeoutMs = 30_000;
    /** Time allowed for the tracking form to appear before it is a parse error. */
    private int formWaitMs = 10_000;
    /** Gap between carrier requests, plus random jitter (§3.2). */
    private int minQueryIntervalMs = 500;
    private int queryJitterMs = 1_000;
    /** Backoff used when the carrier answers 429 without a Retry-After header. */
    private long rateLimitBackoffMs = 30_000;

    /** Upper bound on the captured page text, to keep the raw payload small. */
    private int maxBodyChars = 32_768;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getContainerInputSelector() {
        return containerInputSelector;
    }

    public void setContainerInputSelector(String containerInputSelector) {
        this.containerInputSelector = containerInputSelector;
    }

    public String getSubmitSelector() {
        return submitSelector;
    }

    public void setSubmitSelector(String submitSelector) {
        this.submitSelector = submitSelector;
    }

    public String getResultSelector() {
        return resultSelector;
    }

    public void setResultSelector(String resultSelector) {
        this.resultSelector = resultSelector;
    }

    public String getStatusSelector() {
        return statusSelector;
    }

    public void setStatusSelector(String statusSelector) {
        this.statusSelector = statusSelector;
    }

    public String getEventRowSelector() {
        return eventRowSelector;
    }

    public void setEventRowSelector(String eventRowSelector) {
        this.eventRowSelector = eventRowSelector;
    }

    public String getTrackingUrlPattern() {
        return trackingUrlPattern;
    }

    public void setTrackingUrlPattern(String trackingUrlPattern) {
        this.trackingUrlPattern = trackingUrlPattern;
    }

    public List<String> getNotFoundTexts() {
        return notFoundTexts;
    }

    public void setNotFoundTexts(List<String> notFoundTexts) {
        this.notFoundTexts = notFoundTexts;
    }

    public List<String> getCaptchaTexts() {
        return captchaTexts;
    }

    public void setCaptchaTexts(List<String> captchaTexts) {
        this.captchaTexts = captchaTexts;
    }

    public List<String> getRateLimitTexts() {
        return rateLimitTexts;
    }

    public void setRateLimitTexts(List<String> rateLimitTexts) {
        this.rateLimitTexts = rateLimitTexts;
    }

    public String getLoginFieldSelector() {
        return loginFieldSelector;
    }

    public void setLoginFieldSelector(String loginFieldSelector) {
        this.loginFieldSelector = loginFieldSelector;
    }

    public int getResultWaitMs() {
        return resultWaitMs;
    }

    public void setResultWaitMs(int resultWaitMs) {
        this.resultWaitMs = resultWaitMs;
    }

    public int getQueryTimeoutMs() {
        return Math.min(30_000, Math.max(1_000, queryTimeoutMs));
    }

    public void setQueryTimeoutMs(int queryTimeoutMs) {
        this.queryTimeoutMs = queryTimeoutMs;
    }

    public int getFormWaitMs() {
        return formWaitMs;
    }

    public void setFormWaitMs(int formWaitMs) {
        this.formWaitMs = formWaitMs;
    }

    public int getMinQueryIntervalMs() {
        return minQueryIntervalMs;
    }

    public void setMinQueryIntervalMs(int minQueryIntervalMs) {
        this.minQueryIntervalMs = minQueryIntervalMs;
    }

    public int getQueryJitterMs() {
        return queryJitterMs;
    }

    public void setQueryJitterMs(int queryJitterMs) {
        this.queryJitterMs = queryJitterMs;
    }

    public long getRateLimitBackoffMs() {
        return rateLimitBackoffMs;
    }

    public void setRateLimitBackoffMs(long rateLimitBackoffMs) {
        this.rateLimitBackoffMs = rateLimitBackoffMs;
    }

    public int getMaxBodyChars() {
        return maxBodyChars;
    }

    public void setMaxBodyChars(int maxBodyChars) {
        this.maxBodyChars = maxBodyChars;
    }
}
