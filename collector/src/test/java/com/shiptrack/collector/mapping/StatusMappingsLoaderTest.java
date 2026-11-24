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

    private long clock = 1;

    @Test
    void loadsVersionAndMappings() throws IOException {
        Path file = write(2, "Gate in", "GATE_IN", 90);

        StatusMappingsLoader loader = new StatusMappingsLoader(file.toString());
        loader.loadOnStartup();

        StatusMappingSet snapshot = loader.snapshot();
        assertThat(snapshot.version()).isEqualTo(2);
        assertThat(snapshot.normalize("MSC", "Gate in at Ningbo")).isEqualTo(TransportStatus.GATE_IN);
    }

    @Test
    void refreshAndGetSeesANewVersionAtTheNextTaskStart() throws IOException {
        Path file = write(1, "Gate in", "GATE_IN", 90);
        StatusMappingsLoader loader = new StatusMappingsLoader(file.toString());
        loader.loadOnStartup();
        StatusMappingSet firstTask = loader.refreshAndGet();

        write(file, 2, "Available for pick up", "AVAILABLE", 95);
        StatusMappingSet secondTask = loader.refreshAndGet();

        assertThat(firstTask.version()).isEqualTo(1);
        assertThat(firstTask.normalize("MSC", "Available for pick up")).isEqualTo(TransportStatus.UNKNOWN);
        assertThat(secondTask.version()).isEqualTo(2);
        assertThat(secondTask.normalize("MSC", "Available for pick up")).isEqualTo(TransportStatus.AVAILABLE);
    }

    @Test
    void aPinnedSnapshotKeepsWorkingWhileTheFileChangesMidTask() throws IOException {
        Path file = write(1, "Gate in", "GATE_IN", 90);
        StatusMappingsLoader loader = new StatusMappingsLoader(file.toString());
        loader.loadOnStartup();

        StatusMappingSet pinned = loader.refreshAndGet();
        write(file, 2, "Gate in", "LOADED", 90);
        StatusMappingSet nextTask = loader.refreshAndGet();

        // The in-flight task keeps the rules it started with, even though the
        // mount now publishes a newer version.
        assertThat(pinned.version()).isEqualTo(1);
        assertThat(pinned.normalize("MSC", "Gate in")).isEqualTo(TransportStatus.GATE_IN);
        assertThat(nextTask.version()).isEqualTo(2);
        assertThat(nextTask.normalize("MSC", "Gate in")).isEqualTo(TransportStatus.LOADED);
    }

    @Test
    void rejectsAVersionRollbackAndKeepsTheNewerSnapshot() throws IOException {
        Path file = write(2, "Gate in", "GATE_IN", 90);
        StatusMappingsLoader loader = new StatusMappingsLoader(file.toString());
        loader.loadOnStartup();

        write(file, 1, "Gate in", "LOADED", 90);
        StatusMappingsLoader reloaded = loader;

        assertThat(reloaded.refreshAndGet().version()).isEqualTo(2);
        assertThat(reloaded.snapshot().normalize("MSC", "Gate in")).isEqualTo(TransportStatus.GATE_IN);
    }

    @Test
    void rejectsChangedRulesUnderAnAlreadyPublishedVersion() throws IOException {
        Path file = write(2, "Gate in", "GATE_IN", 90);
        StatusMappingsLoader loader = new StatusMappingsLoader(file.toString());
        loader.loadOnStartup();

        write(file, 2, "Gate in", "LOADED", 90);

        assertThat(loader.refreshAndGet().normalize("MSC", "Gate in"))
                .isEqualTo(TransportStatus.GATE_IN);
    }

    @Test
    void keepsThePreviousSnapshotWhenTheNewFileIsInvalid() throws IOException {
        Path file = write(1, "Gate in", "GATE_IN", 90);
        StatusMappingsLoader loader = new StatusMappingsLoader(file.toString());
        loader.loadOnStartup();

        overwrite(file, """
                version: 0
                mappings: []
                """);

        assertThat(loader.refreshAndGet().version()).isEqualTo(1);
    }

    @Test
    void recoversAfterABrokenFileIsFixed() throws IOException {
        Path file = write(1, "Gate in", "GATE_IN", 90);
        StatusMappingsLoader loader = new StatusMappingsLoader(file.toString());
        loader.loadOnStartup();

        overwrite(file, "version: [this is not valid yaml");
        assertThat(loader.refreshAndGet().version()).isEqualTo(1);

        // The failed read must not have committed the file identity, so this
        // fix is detected even though the file was already "seen".
        write(file, 2, "Gate in", "GATE_IN", 90);

        assertThat(loader.refreshAndGet().version()).isEqualTo(2);
    }

    @Test
    void rejectsRulesThatDisagreeAtTheSamePriority() throws IOException {
        Path file = write(() -> """
                version: 1
                updated_at: "2026-11-24T20:00:00+08:00"
                mappings:
                  - carrier: MSC
                    match: "Gate in"
                    transport_status: GATE_IN
                    priority: 90
                  - carrier: msc
                    match: "GATE  IN"
                    transport_status: LOADED
                    priority: 90
                """);
        StatusMappingsLoader loader = new StatusMappingsLoader(file.toString());

        assertThatThrownBy(loader::loadOnStartup)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting rules");
    }

    @Test
    void failsFastWhenTheMountIsMissing() {
        StatusMappingsLoader loader = new StatusMappingsLoader(tempDir.resolve("absent.yaml").toString());
        assertThatThrownBy(loader::loadOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot read status mappings");
    }

    private Path write(int version, String match, String status, int priority) throws IOException {
        return write(() -> """
                version: %d
                updated_at: "2026-11-24T20:00:00+08:00"
                mappings:
                  - carrier: MSC
                    match: "%s"
                    transport_status: %s
                    priority: %d
                """.formatted(version, match, status, priority));
    }

    private void write(Path file, int version, String match, String status, int priority) throws IOException {
        overwrite(file, """
                version: %d
                updated_at: "2026-11-24T20:05:00+08:00"
                mappings:
                  - carrier: MSC
                    match: "%s"
                    transport_status: %s
                    priority: %d
                """.formatted(version, match, status, priority));
    }

    private Path write(MappingWriter writer) throws IOException {
        Path file = tempDir.resolve("status-mappings.yaml");
        overwrite(file, writer.content());
        return file;
    }

    private void overwrite(Path file, String yaml) throws IOException {
        Files.writeString(file, yaml);
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(++clock)));
    }

    @FunctionalInterface
    private interface MappingWriter {
        String content();
    }
}
