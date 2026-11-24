package com.shiptrack.collector;

import com.shiptrack.collector.api.CollectorRunner;
import com.shiptrack.collector.api.InternalApiClient;
import com.shiptrack.collector.browser.PlaywrightBrowserSessionFactory;
import com.shiptrack.collector.mapping.StatusMappingsLoader;
import com.shiptrack.collector.msc.MscProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the collector wiring without opening Chromium: the browser process is
 * lazy, so this proves property binding and bean dependencies, not the network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CollectorContextTest {

    private static final Path MAPPING_FILE = createMappingFile();

    @DynamicPropertySource
    static void mappingPath(DynamicPropertyRegistry registry) {
        registry.add("collector.mapping-path", MAPPING_FILE::toString);
        registry.add("collector.internal-token", () -> "test-token");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresTheCollectorWithoutTouchingTheDatabase() {
        assertThat(context.getBean(InternalApiClient.class)).isNotNull();
        assertThat(context.getBean(MscProbe.class)).isNotNull();
        assertThat(context.getBean(CollectorRunner.class)).isNotNull();
        assertThat(context.getBean(PlaywrightBrowserSessionFactory.class)).isNotNull();
        assertThat(context.getBean(StatusMappingsLoader.class).snapshot().version()).isEqualTo(1);

        // Quality red line: the collector module must never grow a JDBC dependency.
        assertThatThrownBy(() -> Class.forName("org.springframework.jdbc.core.JdbcTemplate"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    private static Path createMappingFile() {
        try {
            Path directory = Files.createTempDirectory("collector-context-test");
            Path file = directory.resolve("status-mappings.yaml");
            Files.writeString(file, """
                    version: 1
                    updated_at: "2026-11-24T20:00:00+08:00"
                    mappings:
                      - carrier: MSC
                        match: "Gate in"
                        transport_status: GATE_IN
                        priority: 90
                    """);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
