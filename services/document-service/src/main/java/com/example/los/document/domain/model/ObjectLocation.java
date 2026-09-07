package com.example.los.document.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where a document's bytes live in S3.
 *
 * <p><strong>Object keys are built from opaque identifiers only.</strong> Never
 * an applicant's name, email address, national identifier, or the original
 * filename they chose. That rule exists because an S3 key is far more public
 * than it looks: it appears in server access logs, in CloudTrail data events, in
 * bucket inventory reports, in lifecycle and replication metrics, in every
 * presigned URL ever issued, and in the browser history of anyone who followed
 * one. A key of {@code proof-of-income-jane-smith.pdf} leaks a name to every one
 * of those, permanently and irrevocably.
 *
 * <p>Keys are also prefixed by area:
 *
 * <pre>
 *   quarantine/{applicationId}/{documentId}   before scanning
 *   accepted/{applicationId}/{documentId}     after a clean scan
 * </pre>
 *
 * <p>The separation is what lets a bucket policy, a lifecycle rule and an IAM
 * policy treat unscanned content differently from cleared content — for example
 * denying every principal except the scanner any read access under
 * {@code quarantine/}. A single flat prefix would make that impossible to
 * express.
 */
public record ObjectLocation(String bucket, String objectKey) {

    public static final String QUARANTINE_PREFIX = "quarantine";
    public static final String ACCEPTED_PREFIX = "accepted";

    /**
     * Keys may contain only characters that are unambiguous everywhere: in a URL,
     * in a bucket policy condition, and in a log line. This also rules out any
     * key derived from user-supplied text.
     */
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9/_-]{1,512}$");

    public ObjectLocation {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        if (bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
        if (!SAFE_KEY.matcher(objectKey).matches()) {
            // The offending key is not included: if it did contain personal data,
            // putting it in an exception message would defeat the whole rule.
            throw new IllegalArgumentException(
                    "Object key contains characters that are not permitted. Keys are built from opaque "
                            + "identifiers only and must never be derived from user-supplied text.");
        }
    }

    /** The quarantine location for a newly requested upload. */
    public static ObjectLocation quarantine(String bucket, String applicationId, DocumentId documentId) {
        return new ObjectLocation(bucket, QUARANTINE_PREFIX + "/" + applicationId + "/" + documentId);
    }

    /** Where the object belongs once it has been scanned clean. */
    public ObjectLocation acceptedCounterpart() {
        if (!isQuarantined()) {
            throw new IllegalStateException("Only a quarantined object has an accepted counterpart");
        }
        return new ObjectLocation(bucket, ACCEPTED_PREFIX + objectKey.substring(QUARANTINE_PREFIX.length()));
    }

    public boolean isQuarantined() {
        return objectKey.startsWith(QUARANTINE_PREFIX + "/");
    }

    /** Safe to log in full: by construction it contains identifiers and nothing else. */
    @Override
    public String toString() {
        return "s3://" + bucket + "/" + objectKey;
    }
}
