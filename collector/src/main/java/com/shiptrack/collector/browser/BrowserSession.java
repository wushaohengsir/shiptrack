package com.shiptrack.collector.browser;

import com.microsoft.playwright.Page;

/**
 * One isolated browser context and its single page. Closing the session closes
 * the context but keeps the shared Chromium process alive.
 */
public interface BrowserSession extends AutoCloseable {

    Page page();

    @Override
    void close();
}
