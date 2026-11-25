package com.shiptrack.collector.browser;

import com.microsoft.playwright.BrowserType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxLaunchOptionsTest {

    @Test
    void keepsTheSandboxAndDropsTheSharedMemoryWorkaround() {
        BrowserType.LaunchOptions options =
                new PlaywrightBrowserSessionFactory(new BrowserProperties()).launchOptions();

        assertThat(options.chromiumSandbox).isTrue();
        assertThat(options.ignoreDefaultArgs).contains("--disable-dev-shm-usage");

        List<String> explicitArgs = options.args == null ? List.of() : options.args;
        assertThat(explicitArgs)
                .noneMatch(arg -> arg.contains("--no-sandbox"))
                .noneMatch(arg -> arg.contains("--disable-dev-shm-usage"))
                .noneMatch(arg -> arg.contains("AutomationControlled"));
    }
}
