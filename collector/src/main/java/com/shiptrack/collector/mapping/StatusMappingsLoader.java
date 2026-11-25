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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Reads the read-only {@code status-mappings.yaml} mount and keeps the last
 * good snapshot.
 *
 * <p>Reload is atomic with respect to a task: the caller checks at the start of
 * each task ({@link #refreshAndGet()}) and receives one immutable snapshot for
 * the whole task. A malformed file, a version rollback, or a same-version rule
 * change never replaces the working snapshot; the file identity is only
 * committed after validation succeeds, so a broken file is retried on every
 * tick and recovers as soon as it is fixed.</p>
 */
@Component
public class StatusMappingsLoader {

    private static final Logger log = LoggerFactory.getLogger(StatusMappingsLoader.class);

    private final Path path;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).registerModule(new JavaTimeModule());

    private volatile StatusMappingSet current;
    private volatile FileIdentity identity;

    public StatusMappingsLoader(@Value("${collector.mapping-path}") String mappingPath) {
        this.path = Path.of(mappingPath);
    }

    /** Fail fast when the mount is missing or invalid. */
    @PostConstruct
    public synchronized void loadOnStartup() {
        if (current != null) {
            return;
        }
        Loaded loaded = readValidated();
        current = loaded.set();
        identity = loaded.identity();
        log.info("Loaded {} status mappings, version {}", loaded.set().mappings().size(), loaded.set().version());
    }

    /**
     * Called at the start of every task. Returns the snapshot to pin for that
     * task, reloading first when the mount changed. Any problem keeps the
     * previous snapshot instead of failing the task.
     */
    public synchronized StatusMappingSet refreshAndGet() {
        try {
            if (!Files.isRegularFile(path)) {
                return snapshot();
            }
            FileIdentity observed = readIdentity();
            StatusMappingSet existing = current;
            if (existing != null && observed.equals(identity)) {
                return existing;
            }
            Loaded loaded = readValidated();
            if (existing != null) {
                validateTransition(existing, loaded.set());
            }
            current = loaded.set();
            identity = loaded.identity();
            log.info("Reloaded status mappings, version {}", loaded.set().version());
        } catch (RuntimeException | IOException e) {
            // Keep serving the previous snapshot; the next tick retries.
            log.warn("Status mapping reload failed, keeping the previous snapshot: {}", e.getMessage());
        }
        return snapshot();
    }

    @Scheduled(fixedDelayString = "${collector.mapping-reload-interval-ms:10000}")
    public synchronized void reloadIfChanged() {
        refreshAndGet();
    }

    public StatusMappingSet snapshot() {
        StatusMappingSet snapshot = current;
        if (snapshot == null) {
            throw new IllegalStateException("status mappings have not been loaded yet");
        }
        return snapshot;
    }

    private Loaded readValidated() {
        try {
            FileIdentity before = readIdentity();
            byte[] content = Files.readAllBytes(path);
            FileIdentity after = identityOf(content);
            if (!before.equals(after)) {
                // The API may have atomically replaced the file while we read.
                // Retry on the next tick rather than pairing one version's
                // rules with another version's identity.
                throw new IllegalStateException("status mappings changed while reading");
            }
            StatusMappingsDto dto = yaml.readValue(content, StatusMappingsDto.class);
            StatusMappingSet set = validate(dto);
            return new Loaded(set, after);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read status mappings from " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * mtime and size alone miss a same-length replacement that restores the
     * timestamp, so the digest of the bytes is part of the identity.
     */
    private FileIdentity readIdentity() throws IOException {
        return identityOf(Files.readAllBytes(path));
    }

    private FileIdentity identityOf(byte[] content) throws IOException {
        return new FileIdentity(Files.getLastModifiedTime(path), content.length, sha256(content));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void validateTransition(StatusMappingSet existing, StatusMappingSet loaded) {
        if (loaded.version() < existing.version()) {
            throw new IllegalArgumentException("version would go backwards: " + existing.version()
                    + " -> " + loaded.version());
        }
        if (loaded.version() == existing.version() && !loaded.mappings().equals(existing.mappings())) {
            throw new IllegalArgumentException("version " + loaded.version()
                    + " already published with different rules");
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
        Map<String, StatusMapping> seen = new HashMap<>();
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
            StatusMapping mapping = new StatusMapping(row.carrier(), row.match(), row.transportStatus(), row.priority());
            StatusMapping duplicate = seen.putIfAbsent(conflictKey(mapping), mapping);
            if (duplicate != null && duplicate.transportStatus() != mapping.transportStatus()) {
                throw new IllegalArgumentException("conflicting rules for carrier=" + row.carrier()
                        + " match='" + row.match() + "' priority=" + row.priority());
            }
            rows.add(mapping);
        }
        return new StatusMappingSet(dto.version(), rows);
    }

    private static String conflictKey(StatusMapping mapping) {
        String carrier = mapping.carrier().isBlank() ? "*" : mapping.carrier().trim().toLowerCase(java.util.Locale.ROOT);
        return carrier + '\u0000' + StatusMappingSet.normalizeText(mapping.match()) + '\u0000' + mapping.priority();
    }

    private record FileIdentity(FileTime lastModified, long size, String sha256) {
    }

    private record Loaded(StatusMappingSet set, FileIdentity identity) {
    }
}
