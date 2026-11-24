package com.shiptrack.collector.browser;

/**
 * Opens a fresh browser context per query. Implementations may keep the
 * underlying Chromium process for the lifetime of the collector.
 */
public interface BrowserSessionFactory extends AutoCloseable {

    BrowserSession open();

    @Override
    void close();
}
