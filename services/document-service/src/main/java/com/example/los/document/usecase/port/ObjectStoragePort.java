package com.example.los.document.usecase.port;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import com.example.los.document.domain.model.ObjectLocation;
import com.example.los.document.domain.model.StoredObject;

/**
 * Outbound port for the object store.
 *
 * <p>Deliberately narrow. There is no method that reads or writes object
 * content, because this service must never handle document bytes: clients upload
 * straight to S3 through a presigned URL, and the scanner reads from S3 itself.
 * A port with a {@code getContent} method would make it easy for a later change
 * to start streaming files through the JVM heap and through API Gateway, whose
 * payload limit would then decide the maximum document size.
 */
public interface ObjectStoragePort {

    /**
     * A short-lived credential permitting exactly one upload.
     *
     * @param url       the presigned URL. <strong>Never logged, never persisted,
     *                  never published on an event, and never returned in an
     *                  error response.</strong> It is a bearer credential.
     * @param expiresIn how long the URL remains valid
     * @param requiredHeaders headers the client must send for the signature to
     *                  match; this is what binds the upload to the declared
     *                  content type rather than leaving it to the client's word
     */
    record PresignedUpload(URI url, Duration expiresIn, java.util.Map<String, String> requiredHeaders) {

        public PresignedUpload {
            requiredHeaders = java.util.Map.copyOf(requiredHeaders);
        }

        /** Redacted: printing this object would put a write credential in a log. */
        @Override
        public String toString() {
            return "PresignedUpload[redacted, expiresIn=" + expiresIn + "]";
        }
    }

    /**
     * Issues a presigned PUT for exactly one object.
     *
     * <p>Implementations must bind the signature to the content type and to the
     * exact key, so the credential cannot be used to write a different object or
     * to upload something other than what was declared.
     */
    PresignedUpload presignUpload(ObjectLocation location, String contentType, Duration validity);

    /**
     * What the store actually holds at this location.
     *
     * @return empty when no object exists there, which is how an abandoned
     *         upload is distinguished from a completed one
     */
    Optional<StoredObject> describe(ObjectLocation location);

    /**
     * Moves a cleared object from the quarantine area to the accepted area.
     *
     * <p>A move rather than a flag, so that a bucket policy and an IAM policy can
     * express "nothing may read under quarantine/ except the scanner". A status
     * column alone cannot be enforced by the storage layer.
     */
    void moveToAccepted(ObjectLocation quarantined, ObjectLocation accepted);

    /** Removes an object. Used only for abandoned uploads that never arrived. */
    void delete(ObjectLocation location);
}
