package com.shiptrack.collector.browser;

/**
 * Browser tests need the Playwright Chromium download, which CI does not
 * install for this module. They are opt-in through
 * {@code COLLECTOR_BROWSER_TEST=true}; once enabled, a browser that cannot
 * start with its sandbox fails the test instead of being skipped. The
 * container that provides the required seccomp and 512 MB shared memory is
 * {@code collector/Dockerfile.browser-test}.
 */
public final class BrowserTestSupport {

    private BrowserTestSupport() {
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getenv("COLLECTOR_BROWSER_TEST"));
    }
}
