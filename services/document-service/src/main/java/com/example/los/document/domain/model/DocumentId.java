package com.example.los.document.domain.model;

import java.util.UUID;

/** Opaque identifier of a document. Also the only identifier that appears in its S3 key. */
public record DocumentId(UUID value) {

    public DocumentId {
        if (value == null) {
            throw new IllegalArgumentException("DocumentId value must not be null");
        }
    }

    public static DocumentId newId() {
        return new DocumentId(UUID.randomUUID());
    }

    public static DocumentId of(String value) {
        try {
            return new DocumentId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid document identifier", e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
