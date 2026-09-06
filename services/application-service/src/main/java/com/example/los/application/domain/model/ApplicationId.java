package com.example.los.application.domain.model;

import java.util.UUID;

/**
 * Opaque identifier of a loan application.
 *
 * <p>Backed by a time-ordered UUID (RFC 9562 version 7) rather than a sequential
 * database key for two reasons: a sequential public identifier leaks how many
 * applications the platform has received and lets a caller probe for other
 * people's applications, and a purely random identifier fragments the primary
 * key index. A version 7 UUID is unguessable while still sorting by creation
 * time, so inserts stay clustered.
 */
public record ApplicationId(UUID value) {

    public ApplicationId {
        if (value == null) {
            throw new IllegalArgumentException("ApplicationId value must not be null");
        }
    }

    public static ApplicationId newId() {
        return new ApplicationId(TimeOrderedUuid.next());
    }

    public static ApplicationId of(String value) {
        try {
            return new ApplicationId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid application identifier", e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
