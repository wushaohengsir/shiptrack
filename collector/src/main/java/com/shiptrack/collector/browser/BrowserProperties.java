package com.shiptrack.collector.browser;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Browser knobs for the collector. Everything the container needs is passed
 * through environment variables so the image holds no site-specific secrets.
 */
@ConfigurationProperties(prefix = "collector.browser")
public class BrowserProperties {

    /** Run Chromium headless; override only for local debugging. */
    private boolean headless = true;

    /** A plain desktop Chrome UA; the default identifies Playwright. */
    private String userAgent =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    private int viewportWidth = 1280;
    private int viewportHeight = 800;
    private String locale = "en-US";
    private String timezone = "Asia/Shanghai";

    /** Default action timeout. Individual navigations can override it. */
    private int actionTimeoutMs = 15_000;

    private int navigationTimeoutMs = 45_000;

    /** Abort these request resource types. Stylesheets are kept on purpose. */
    private List<String> blockedResourceTypes = new ArrayList<>(List.of("image", "media", "font"));

    /** Substring/glob-free host fragments whose requests are always aborted. */
    private List<String> blockedHosts = new ArrayList<>(List.of(
            "google-analytics.com",
            "googletagmanager.com",
            "doubleclick.net",
            "hotjar.com",
            "facebook.net",
            "facebook.com/tr",
            "segment.io",
            "newrelic.com",
            "sentry.io"));

    public boolean isHeadless() {
        return headless;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public int getViewportWidth() {
        return viewportWidth;
    }

    public void setViewportWidth(int viewportWidth) {
        this.viewportWidth = viewportWidth;
    }

    public int getViewportHeight() {
        return viewportHeight;
    }

    public void setViewportHeight(int viewportHeight) {
        this.viewportHeight = viewportHeight;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public int getActionTimeoutMs() {
        return actionTimeoutMs;
    }

    public void setActionTimeoutMs(int actionTimeoutMs) {
        this.actionTimeoutMs = actionTimeoutMs;
    }

    public int getNavigationTimeoutMs() {
        return navigationTimeoutMs;
    }

    public void setNavigationTimeoutMs(int navigationTimeoutMs) {
        this.navigationTimeoutMs = navigationTimeoutMs;
    }

    public List<String> getBlockedResourceTypes() {
        return blockedResourceTypes;
    }

    public void setBlockedResourceTypes(List<String> blockedResourceTypes) {
        this.blockedResourceTypes = blockedResourceTypes;
    }

    public List<String> getBlockedHosts() {
        return blockedHosts;
    }

    public void setBlockedHosts(List<String> blockedHosts) {
        this.blockedHosts = blockedHosts;
    }
}
