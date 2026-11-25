package com.shiptrack.api.support;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Container-number normalisation and ISO 6346 check digit.
 */
public final class ContainerNumbers {

    private static final Pattern FORMAT = Pattern.compile("^[A-Z]{4}[0-9]{7}$");
    private static final int[] LETTER_VALUES = new int[26];

    static {
        int[] values = {10, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24,
                25, 26, 27, 28, 29, 30, 31, 32, 34, 35, 36, 37, 38};
        System.arraycopy(values, 0, LETTER_VALUES, 0, values.length);
    }

    private ContainerNumbers() {
    }

    /** Uppercase, strip spaces and hyphens (§5.1). */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public static boolean hasValidFormat(String normalized) {
        return normalized != null && FORMAT.matcher(normalized).matches();
    }

    /** True when the 11th character equals the ISO 6346 check digit. */
    public static boolean hasValidChecksum(String normalized) {
        if (!hasValidFormat(normalized)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = normalized.charAt(i);
            int value;
            if (c >= 'A' && c <= 'Z') {
                value = LETTER_VALUES[c - 'A'];
            } else {
                value = c - '0';
            }
            sum += value * (1 << i);
        }
        int checkDigit = sum % 11;
        if (checkDigit == 10) {
            checkDigit = 0;
        }
        return checkDigit == normalized.charAt(10) - '0';
    }
}
