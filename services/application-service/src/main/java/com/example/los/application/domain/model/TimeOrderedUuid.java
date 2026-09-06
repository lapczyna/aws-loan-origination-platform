package com.example.los.application.domain.model;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates RFC 9562 version 7 UUIDs: 48 bits of Unix milliseconds followed by
 * 74 bits of randomness.
 *
 * <p>The JDK does not yet provide a version 7 factory, so it is implemented here
 * rather than pulled in as a dependency for twenty lines of bit manipulation.
 */
final class TimeOrderedUuid {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TimeOrderedUuid() {}

    static UUID next() {
        long timestamp = System.currentTimeMillis();
        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);

        long mostSignificant = (timestamp & 0xFFFF_FFFF_FFFFL) << 16
                | (long) (randomBytes[0] & 0x0F) << 8
                | (randomBytes[1] & 0xFFL);
        mostSignificant = mostSignificant & ~(0xFL << 12) | (7L << 12); // version 7

        long leastSignificant = 0;
        for (int i = 2; i < 10; i++) {
            leastSignificant = leastSignificant << 8 | (randomBytes[i] & 0xFFL);
        }
        leastSignificant = leastSignificant & ~(0x3L << 62) | (2L << 62); // IETF variant

        return new UUID(mostSignificant, leastSignificant);
    }
}
