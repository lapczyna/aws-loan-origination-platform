package com.example.los.document.domain.model;

import java.util.Objects;

/**
 * What S3 actually reports about the stored object.
 *
 * <p>Distinct from what the client declared when requesting the upload. Keeping
 * both is what makes verification possible at all: comparing the request against
 * itself proves nothing.
 *
 * @param sizeBytes      size S3 reports
 * @param contentType    content type S3 reports
 * @param eTag           entity tag S3 reports
 * @param checksumSha256 SHA-256 S3 computed, when checksum mode was requested
 */
public record StoredObject(long sizeBytes, String contentType, String eTag, String checksumSha256) {

    public StoredObject {
        Objects.requireNonNull(contentType, "contentType must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
