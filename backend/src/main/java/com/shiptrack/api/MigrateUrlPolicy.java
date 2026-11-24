package com.shiptrack.api;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one JDBC URL shape the {@code migrate} profile accepts.
 *
 * <p>MySQL Connector/J resolves credentials from several places in a URL, not only the
 * user-info section: host-level key/value attributes
 * ({@code jdbc:mysql://address=(host=h)(user=u)/db}) override the global connection
 * properties, and percent-encoded query keys hide an embedded {@code user}/{@code password}.
 * Rather than trying to enumerate those driver features, this policy accepts only the
 * plain project form and an explicit allow-list of non-credential parameters:
 *
 * <pre>{@code jdbc:mysql://<host>[:<port>]/<database>[?<approved parameter>=<value>...]}</pre>
 *
 * <p>Used both by {@link MigrateCredentialGuardConfiguration} (startup validation) and
 * by {@link MigrateDataSourceConfiguration} (the single controlled migration datasource)
 * so the URL that is checked is the URL that is actually connected to.
 */
final class MigrateUrlPolicy {

    private static final String PREFIX = "jdbc:mysql://";
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern PORT = Pattern.compile("\\d{1,5}");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_$-]+");
    private static final Pattern PARAMETER_VALUE = Pattern.compile("[A-Za-z0-9._+-]*");
    private static final Set<String> APPROVED_PARAMETERS = Set.of(
            "useunicode",
            "characterencoding",
            "servertimezone",
            "usessl",
            "allowpublickeyretrieval");

    private MigrateUrlPolicy() {
    }

    /**
     * Validates the URL and returns it unchanged; throws with a precise message otherwise.
     */
    static String requireSupported(String url) {
        if (url == null || url.isBlank() || url.startsWith("${")) {
            throw new IllegalStateException("migrate profile requires spring.datasource.url;"
                    + " the migration target is not configured");
        }
        if (!url.startsWith(PREFIX)) {
            throw unsupported();
        }
        // A fragment and percent-encoding are never part of the project format; the
        // latter could hide a credential key (e.g. %75ser=) from a plain text scan.
        if (url.indexOf('#') >= 0 || url.indexOf('%') >= 0) {
            throw unsupported();
        }

        String rest = url.substring(PREFIX.length());
        int pathStart = rest.indexOf('/');
        if (pathStart < 0) {
            throw unsupported();
        }
        String authority = rest.substring(0, pathStart);
        String pathAndQuery = rest.substring(pathStart + 1);

        if (authority.indexOf('@') >= 0) {
            throw embeddedCredentials();
        }
        // Connector/J host-level attributes use '(' ')' ',' '=' inside the authority and
        // are applied after the global properties, so they can replace the account.
        for (int i = 0; i < authority.length(); i++) {
            char character = authority.charAt(i);
            if (character == '(' || character == ')' || character == ',' || character == '=') {
                throw hostLevelAttributes();
            }
        }

        String host = authority;
        String port = null;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
        }
        if (!HOST.matcher(host).matches()
                || (port != null && !PORT.matcher(port).matches())
                || !DATABASE.matcher(databasePart(pathAndQuery)).matches()) {
            throw unsupported();
        }

        int question = pathAndQuery.indexOf('?');
        if (question >= 0) {
            requireApprovedParameters(pathAndQuery.substring(question + 1));
        }
        return url;
    }

    private static String databasePart(String pathAndQuery) {
        int question = pathAndQuery.indexOf('?');
        return question < 0 ? pathAndQuery : pathAndQuery.substring(0, question);
    }

    private static void requireApprovedParameters(String query) {
        if (query.isEmpty()) {
            throw unsupported();
        }
        for (String pair : query.split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                throw unsupported();
            }
            String key = pair.substring(0, equals);
            if (key.equalsIgnoreCase("user") || key.equalsIgnoreCase("password")) {
                throw embeddedCredentials();
            }
            if (!APPROVED_PARAMETERS.contains(key.toLowerCase(Locale.ROOT))
                    || !PARAMETER_VALUE.matcher(pair.substring(equals + 1)).matches()) {
                throw unsupported();
            }
        }
    }

    private static IllegalStateException embeddedCredentials() {
        return new IllegalStateException("migrate profile forbids credentials embedded in "
                + "spring.datasource.url; use DB_MIGRATION_USER and DB_MIGRATION_PASSWORD instead");
    }

    private static IllegalStateException hostLevelAttributes() {
        return new IllegalStateException("migrate profile forbids host-level attributes in "
                + "spring.datasource.url; only <host>[:<port>]/<database> is allowed");
    }

    private static IllegalStateException unsupported() {
        return new IllegalStateException("migrate profile requires spring.datasource.url to be "
                + "jdbc:mysql://<host>[:<port>]/<database> with only approved connection parameters");
    }
}
