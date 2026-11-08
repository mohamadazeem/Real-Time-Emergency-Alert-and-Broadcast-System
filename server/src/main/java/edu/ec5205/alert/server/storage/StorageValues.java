package edu.ec5205.alert.server.storage;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.UUID;

/** Storage validation does not generate identities or alter the shared model fields. */
final class StorageValues {
    private static final DateTimeFormatter SORT_TIME = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private StorageValues() {}

    static String id(String value) {
        if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Expected a UUID in 8-4-4-4-12 format.");
        }
        return value;
    }

    static String timeKey(String value) {
        OffsetDateTime time = OffsetDateTime.parse(value);
        if (!time.getOffset().equals(ZoneOffset.UTC)) throw new IllegalArgumentException("Expected an ISO-8601 UTC timestamp.");
        return SORT_TIME.format(time.toInstant());
    }

    static void text(String value, int limit) {
        if (value.isBlank() || value.codePointCount(0, value.length()) > limit) {
            throw new IllegalArgumentException("Expected non-blank text of 1 to " + limit + " Unicode characters.");
        }
    }
}
