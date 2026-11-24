package com.shiptrack.collector.browser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Browser tests need the Playwright Chromium download, which CI does not
 * install for this module, and a host that permits Chromium's user-namespace
 * sandbox. They run when {@code COLLECTOR_BROWSER_TEST=true} and the host
 * allows unprivileged user namespaces (for example the container in
 * {@code deploy/}).
 */
public final class BrowserTestSupport {

    private BrowserTestSupport() {
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getenv("COLLECTOR_BROWSER_TEST")) && sandboxAvailable();
    }

    /**
     * Ubuntu 23.10+ can restrict unprivileged user namespaces through AppArmor.
     * Chromium then refuses to start with the sandbox enabled; that is a host
     * policy, not a test failure.
     */
    public static boolean sandboxAvailable() {
        Path knob = Path.of("/proc/sys/kernel/apparmor_restrict_unprivileged_userns");
        try {
            return !Files.isRegularFile(knob) || !Files.readString(knob).trim().equals("1");
        } catch (RuntimeException | java.io.IOException e) {
            return true;
        }
    }
}
