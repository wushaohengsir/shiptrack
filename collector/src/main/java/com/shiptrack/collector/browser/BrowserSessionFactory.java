package com.shiptrack.collector.browser;

/**
 * Opens a fresh browser context per query. Implementations may keep the
 * underlying Chromium process for the lifetime of the collector.
 */
public interface BrowserSessionFactory extends AutoCloseable {

    BrowserSession open();

    /**
     * Opens a session while respecting the caller's remaining query budget.
     * Startup (driver, browser launch, context) is part of the 30 s query
     * budget, so an implementation should cap its own startup work to this
     * value.
     */
    default BrowserSession open(long timeoutMs) {
        return open();
    }

    @Override
    void close();
}
