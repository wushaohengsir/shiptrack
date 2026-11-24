package com.shiptrack.collector.mapping;

import com.shiptrack.contract.enums.TransportStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real {@code config/status-mappings.yaml} that ships with the API, so
 * a priority mistake cannot hide behind a test fixture.
 */
class DefaultStatusMappingsTest {

    @Test
    void emptyContainerPickupOutranksTheGenericPickupRule() {
        StatusMappingsLoader loader = new StatusMappingsLoader(shippedMappingFile().toString());
        loader.loadOnStartup();

        assertThat(loader.snapshot().normalize("MSC", "Empty container picked up"))
                .isEqualTo(TransportStatus.EMPTY_PICKED_UP);
        assertThat(loader.snapshot().normalize("MSC", "Empty pick up at Ningbo"))
                .isEqualTo(TransportStatus.EMPTY_PICKED_UP);
        assertThat(loader.snapshot().normalize("MSC", "Container picked up by consignee"))
                .isEqualTo(TransportStatus.PICKED_UP);
        assertThat(loader.snapshot().normalize("MSC", "Empty container returned"))
                .isEqualTo(TransportStatus.EMPTY_RETURNED);
        assertThat(loader.snapshot().normalize("MSC", "Gate in"))
                .isEqualTo(TransportStatus.GATE_IN);
    }

    private static Path shippedMappingFile() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 5 && directory != null; depth++, directory = directory.getParent()) {
            Path candidate = directory.resolve("config").resolve("status-mappings.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("config/status-mappings.yaml not found from "
                + System.getProperty("user.dir"));
    }
}
