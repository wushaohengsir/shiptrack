package com.shiptrack.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The `migrate` profile must fail fast when the dedicated migration credentials are
 * missing: no fallback to the API runtime account is allowed (技术栈使用说明 §3.7).
 */
class MigrateProfileFailsFastTest {

    @Test
    void missingMigrationUserAbortsStartup() {
        assertStartupFailsMentioning(Map.of("DB_MIGRATION_PASSWORD", "secret"), "DB_MIGRATION_USER");
    }

    @Test
    void missingMigrationPasswordAbortsStartup() {
        assertStartupFailsMentioning(Map.of("DB_MIGRATION_USER", "shiptrack_migration"), "DB_MIGRATION_PASSWORD");
    }

    private static void assertStartupFailsMentioning(Map<String, Object> defaultProperties, String missingProperty) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ApiApplication.class)
                .profiles("migrate")
                .web(WebApplicationType.NONE)
                .properties(defaultProperties);

        Throwable failure = assertThrows(Throwable.class, builder::run,
                "migrate profile must not start without " + missingProperty);

        assertTrue(causeChainContains(failure, missingProperty),
                () -> "startup failed for an unrelated reason; expected unresolved " + missingProperty
                        + "\n" + describe(failure));
    }

    private static boolean causeChainContains(Throwable failure, String text) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains(text)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static String describe(Throwable failure) {
        StringBuilder builder = new StringBuilder();
        for (Throwable current = failure; current != null && current.getCause() != current;
                current = current.getCause()) {
            builder.append(current.getClass().getName()).append(": ").append(current.getMessage()).append('\n');
        }
        return builder.toString();
    }
}
