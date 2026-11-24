package com.shiptrack.collector.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shiptrack.contract.dto.StatusMappingDto;
import com.shiptrack.contract.dto.StatusMappingsDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the read-only {@code status-mappings.yaml} mount and keeps the last
 * good snapshot. A malformed update never replaces a working snapshot; the
 * collector keeps reporting the previous version and logs the failure.
 */
@Component
public class StatusMappingsLoader {

    private static final Logger log = LoggerFactory.getLogger(StatusMappingsLoader.class);

    private final Path path;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).registerModule(new JavaTimeModule());

    private volatile StatusMappingSet current;
    private volatile FileTime lastModified;
    private volatile long lastSize = -1;

    public StatusMappingsLoader(@Value("${collector.mapping-path}") String mappingPath) {
        this.path = Path.of(mappingPath);
    }

    /** Fail fast when the mount is missing or invalid. */
    @PostConstruct
    public synchronized void loadOnStartup() {
        if (current != null) {
            return;
        }
        StatusMappingSet loaded = read();
        current = loaded;
        log.info("Loaded {} status mappings, version {}", loaded.mappings().size(), loaded.version());
    }

    /**
     * Poll every {@code collector.mapping-reload-interval-ms} and swap only when
     * the file identity changed. Renames (API atomic update) are handled because
     * mtime and size are compared, never inode identity.
     */
    @Scheduled(fixedDelayString = "${collector.mapping-reload-interval-ms:10000}")
    public synchronized void reloadIfChanged() {
        try {
            if (!Files.isRegularFile(path)) {
                return;
            }
            FileTime modified = Files.getLastModifiedTime(path);
            long size = Files.size(path);
            if (modified.equals(lastModified) && size == lastSize) {
                return;
            }
            StatusMappingSet loaded = read();
            current = loaded;
            log.info("Reloaded status mappings, version {}", loaded.version());
        } catch (RuntimeException | IOException e) {
            // Keep serving the previous snapshot; the next tick retries.
            log.warn("Status mapping reload failed, keeping the previous snapshot: {}", e.getMessage());
        }
    }

    public StatusMappingSet snapshot() {
        StatusMappingSet snapshot = current;
        if (snapshot == null) {
            throw new IllegalStateException("status mappings have not been loaded yet");
        }
        return snapshot;
    }

    private StatusMappingSet read() {
        try {
            lastModified = Files.getLastModifiedTime(path);
            lastSize = Files.size(path);
            StatusMappingsDto dto = yaml.readValue(path.toFile(), StatusMappingsDto.class);
            return validate(dto);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read status mappings from " + path + ": " + e.getMessage(), e);
        }
    }

    private static StatusMappingSet validate(StatusMappingsDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("status mappings file is empty");
        }
        if (dto.version() < 1) {
            throw new IllegalArgumentException("version must be a positive integer");
        }
        if (dto.mappings() == null || dto.mappings().isEmpty()) {
            throw new IllegalArgumentException("mappings must not be empty");
        }
        List<StatusMapping> rows = new ArrayList<>(dto.mappings().size());
        for (int i = 0; i < dto.mappings().size(); i++) {
            StatusMappingDto row = dto.mappings().get(i);
            if (row == null) {
                throw new IllegalArgumentException("mappings[" + i + "] is null");
            }
            if (row.carrier() == null || row.carrier().isBlank()) {
                throw new IllegalArgumentException("mappings[" + i + "].carrier is required");
            }
            if (row.match() == null || row.match().isBlank()) {
                throw new IllegalArgumentException("mappings[" + i + "].match is required");
            }
            if (row.transportStatus() == null) {
                throw new IllegalArgumentException("mappings[" + i + "].transport_status is required");
            }
            rows.add(new StatusMapping(row.carrier(), row.match(), row.transportStatus(), row.priority()));
        }
        return new StatusMappingSet(dto.version(), rows);
    }
}
