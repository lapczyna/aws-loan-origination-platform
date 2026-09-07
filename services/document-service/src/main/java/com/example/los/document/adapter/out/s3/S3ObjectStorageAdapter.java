package com.example.los.document.adapter.out.s3;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.example.los.document.domain.model.ObjectLocation;
import com.example.los.document.domain.model.StoredObject;
import com.example.los.document.usecase.port.ObjectStoragePort;

/**
 * S3 adapter: presigned uploads, object inspection, and promotion out of
 * quarantine.
 *
 * <h2>The presigned URL</h2>
 *
 * <p>A presigned URL is a bearer credential. Anyone holding it can write to that
 * exact key until it expires, with no further authentication. Three consequences
 * shape this class:
 *
 * <ul>
 *   <li><b>It is never logged, persisted, published or returned in an error.</b>
 *       Not at debug, not in a stack trace, not in a Kafka event. The log
 *       statements below name the key and the validity, never the URL.
 *   <li><b>Its lifetime is short.</b> Minutes, not hours. The window in which a
 *       leaked URL is useful is the window in which it is valid.
 *   <li><b>The signature is bound to the content type.</b> The client must send
 *       the same {@code Content-Type} it declared or the signature will not
 *       match. Without that binding, a URL issued for a PDF would happily accept
 *       an executable, and the content-type allow-list would be advisory.
 * </ul>
 *
 * <p>The signature is deliberately <em>not</em> bound to a content length. S3
 * signature v4 can enforce a length, but doing so forces the client to know the
 * exact byte count before it starts, which browsers streaming a file often do
 * not. The size limit is enforced instead when the upload completes, against
 * what S3 actually stored — which is the check that matters anyway.
 */
@Component
class S3ObjectStorageAdapter implements ObjectStoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorageAdapter.class);

    private final S3Client s3;
    private final S3Presigner presigner;

    S3ObjectStorageAdapter(S3Client s3, S3Presigner presigner) {
        this.s3 = s3;
        this.presigner = presigner;
    }

    @Override
    public PresignedUpload presignUpload(ObjectLocation location, String contentType, Duration validity) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(location.bucket())
                .key(location.objectKey())
                // Part of the signature: a client that sends a different type
                // gets a signature mismatch rather than a stored file.
                .contentType(contentType)
                .build();

        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(validity)
                .putObjectRequest(putRequest)
                .build());

        // The key and the validity are safe to log; the URL is not, and is not.
        log.debug(
                "Issued a presigned upload. objectKey={} validitySeconds={}",
                location.objectKey(),
                validity.toSeconds());

        return new PresignedUpload(toUri(presigned.url()), validity, Map.of("Content-Type", contentType));
    }

    /**
     * Converts the presigned URL without letting it reach an exception message.
     *
     * <p>{@code URL.toURI()} throws a {@link URISyntaxException} whose message
     * contains the offending input -- which here would be the signed credential.
     */
    private static URI toUri(URL url) {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The presigned URL returned by the SDK was not a valid URI");
        }
    }

    @Override
    public Optional<StoredObject> describe(ObjectLocation location) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(location.bucket())
                    .key(location.objectKey())
                    // Asks S3 for the checksum it computed, so integrity is
                    // verified against the store's own value rather than
                    // against anything the client asserted.
                    .checksumMode(software.amazon.awssdk.services.s3.model.ChecksumMode.ENABLED)
                    .build());

            return Optional.of(new StoredObject(
                    head.contentLength(),
                    head.contentType() == null ? "application/octet-stream" : head.contentType(),
                    head.eTag(),
                    head.checksumSHA256()));

        } catch (NoSuchKeyException e) {
            // Not an error: this is exactly how an abandoned upload is detected.
            return Optional.empty();
        } catch (S3Exception e) {
            // The status code is safe to report; the AWS message can quote the
            // request, including the key and headers.
            throw new ObjectStorageUnavailableException(
                    "Could not inspect the stored object. status=" + e.statusCode(), e);
        }
    }

    @Override
    public void moveToAccepted(ObjectLocation quarantined, ObjectLocation accepted) {
        try {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(quarantined.bucket())
                    .sourceKey(quarantined.objectKey())
                    .destinationBucket(accepted.bucket())
                    .destinationKey(accepted.objectKey())
                    .build());

            // Copy first, then delete. The reverse order risks losing a document
            // that has already been accepted if the process dies in between; this
            // order risks only a duplicate the lifecycle rule will clean up.
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(quarantined.bucket())
                    .key(quarantined.objectKey())
                    .build());

            log.info("Promoted a scanned document out of quarantine. objectKey={}", accepted.objectKey());

        } catch (S3Exception e) {
            throw new ObjectStorageUnavailableException(
                    "Could not promote the object out of quarantine. status=" + e.statusCode(), e);
        }
    }

    @Override
    public void delete(ObjectLocation location) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(location.bucket())
                    .key(location.objectKey())
                    .build());
        } catch (S3Exception e) {
            throw new ObjectStorageUnavailableException(
                    "Could not delete the object. status=" + e.statusCode(), e);
        }
    }
}
