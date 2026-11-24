package com.shiptrack.collector.browser;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Evidence that Chromium starts with the built-in sandbox and without the
 * shared-memory workaround. Gated on {@code COLLECTOR_BROWSER_TEST=true}.
 */
class ChromiumSandboxTest {

    @Test
    void launchesSandboxedChromiumAndRendersAPage() {
        assumeTrue(BrowserTestSupport.enabled(), "set COLLECTOR_BROWSER_TEST=true to run browser tests");

        PlaywrightBrowserSessionFactory factory = new PlaywrightBrowserSessionFactory(new BrowserProperties());
        try (BrowserSession session = factory.open()) {
            Page page = session.page();
            page.setContent("<html><body><h1>ship-track sandbox ok</h1></body></html>");
            assertThat(page.locator("h1").innerText()).isEqualTo("ship-track sandbox ok");

            List<String> commands = chromiumCommands();
            assertThat(commands).isNotEmpty();
            assertThat(commands).noneMatch(command -> command.contains("--no-sandbox"));
            assertThat(commands).noneMatch(command -> command.contains("--disable-dev-shm-usage"));
        } finally {
            factory.close();
        }
    }

    private static List<String> chromiumCommands() {
        return ProcessHandle.current().descendants()
                .map(handle -> handle.info().commandLine().orElse(""))
                .filter(command -> command.contains("chrome") || command.contains("chromium"))
                .toList();
    }
}
