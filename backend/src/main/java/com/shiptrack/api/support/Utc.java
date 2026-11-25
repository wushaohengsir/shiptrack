package com.shiptrack.api.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The database session is pinned to {@code +00:00} (application.yaml), so every
 * DATETIME column is read and written as UTC. The wire format is ISO-8601 UTC.
 */
public final class Utc {

    private Utc() {
    }

    public static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    public static Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    public static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /** Reads a DATETIME(3) column as UTC. Null-safe. */
    public static OffsetDateTime read(ResultSet rows, String column) throws SQLException {
        LocalDateTime local = rows.getObject(column, LocalDateTime.class);
        return local == null ? null : local.toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC);
    }

    public static OffsetDateTime at(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
