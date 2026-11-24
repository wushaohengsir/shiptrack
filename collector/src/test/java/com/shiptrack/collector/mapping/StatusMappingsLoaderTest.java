package com.shiptrack.collector.mapping;

import com.shiptrack.contract.enums.TransportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusMappingsLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsVersionAndMappings() throws IOException {
        Path file = write("""
                version: 2
                updated_at: "2026-11-24T20:00:00+08:00"
                mappings:
                  - carrier: MSC
                    match: "Gate in"
                    transport_status: GATE_IN
                    priority: 90
                """);

        StatusMappingsLoader loader = new StatusMappingsLoader(file.toString());
        loader.loadOnStartup();

        StatusMappingSet snapshot = loader.snapshot();
        assertThat(snapshot.version()).isEqualTo(2);
        assertThat(snapshot.normalize("MSC", "Gate in at Ningbo")).isEqualTo(TransportStatus.GATE_IN);
    }

    @Test
    void reloadsWhenTheFileChanges() throws IOException {
        Path file = write("""
                version: 1
                updated_at: "2026-11-24T20:00:00+08:00"
                mappings:
                  - carrier: MSC
                    match: "Gate in"
                    transport_status: GATE_IN
                    priority: 90
                """);
        StatusMappingsLoader loader = new StatusMappingsLoader(file.toString());
        loader.loadOnStartup();

        Files.writeString(file, """
                version: 2
                updated_at: "2026-11-24T20:05:00+08:00"
                mappings:
                  - carrier: MSC
                    match: "Gate in"
                    transport_status: GATE_IN
                    priority: 90
                  - carrier: MSC
                    match: "Available"
                    transport_status: AVAILABLE
                    priority: 95
                """);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        loader.reloadIfChanged();

        assertThat(loader.snapshot().version()).isEqualTo(2);
        assertThat(loader.snapshot().normalize("MSC", "Available for pick up"))
                .isEqualTo(TransportStatus.AVAILABLE);
    }

    @Test
    void keepsThePreviousSnapshotWhenTheNewFileIsInvalid() throws IOException {
        Path file = write("""
                version: 1
                updated_at: "2026-11-24T20:00:00+08:00"
                mappings:
                  - carrier: MSC
                    match: "Gate in"
                    transport_status: GATE_IN
                    priority: 90
                """);
        StatusMappingsLoader loader = new StatusMappingsLoader(file.toString());
        loader.loadOnStartup();

        Files.writeString(file, """
                version: 0
                mappings: []
                """);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        loader.reloadIfChanged();

        assertThat(loader.snapshot().version()).isEqualTo(1);
    }

    @Test
    void failsFastWhenTheMountIsMissing() {
        StatusMappingsLoader loader = new StatusMappingsLoader(tempDir.resolve("absent.yaml").toString());
        assertThatThrownBy(loader::loadOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot read status mappings");
    }

    private Path write(String yaml) throws IOException {
        Path file = tempDir.resolve("status-mappings.yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
