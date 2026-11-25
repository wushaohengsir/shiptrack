package com.shiptrack.api.support;

import java.security.SecureRandom;

/**
 * Crockford-base32 ULID, enough for the documented {@code run_}/{@code clm_}
 * identifiers (26 chars keep the CHAR(30) columns exact).
 */
public final class Ulid {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static String newRunId() {
        return "run_" + next();
    }

    public static String newClaimToken() {
        return "clm_" + next();
    }

    public static String next() {
        long timestamp = System.currentTimeMillis();
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = ENCODING[(int) (timestamp & 0x1F)];
            timestamp >>>= 5;
        }
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        for (int i = 0; i < 16; i++) {
            int value = 0;
            for (int bit = 0; bit < 5; bit++) {
                int position = i * 5 + bit;
                int fromByte = position / 8;
                int inByte = 7 - (position % 8);
                value = (value << 1) | ((random[fromByte] >> inByte) & 1);
            }
            out[10 + i] = ENCODING[value];
        }
        return new String(out);
    }
}
