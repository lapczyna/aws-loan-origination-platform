package com.example.los.document;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import com.example.los.document.domain.model.Document;
import com.example.los.document.domain.model.DocumentId;
import com.example.los.document.domain.model.DocumentStatus;
import com.example.los.document.domain.model.ObjectLocation;
import com.example.los.document.domain.model.UploadConstraints;
import com.example.los.document.domain.model.UploadVerificationFailedException;
import com.example.los.document.usecase.port.ObjectStoragePort;
import com.example.los.document.usecase.service.DocumentScanScheduler;
import com.example.los.document.usecase.service.DocumentUploadUseCase;
import com.example.los.events.vocabulary.DocumentType;
import com.example.los.testsupport.PlatformContainers;
import com.example.los.testsupport.TestJwtIssuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The presigned upload flow, end to end, against real PostgreSQL and a real
 * S3 API provided by LocalStack.
 *
 * <p>These tests perform genuine HTTP {@code PUT}s to presigned URLs from an
 * ordinary HTTP client that holds no AWS credentials — which is exactly the
 * position a browser is in. That is the only way to establish that the flow
 * actually works: a test that called the SDK directly would prove the SDK works
 * and say nothing about whether the URL this service hands a client is usable.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
class DocumentUploadIT {

    private static final String BUCKET = "los-test-documents";

    /**
     * A real OIDC issuer, started per JVM.
     *
     * <p>These tests drive the use cases directly and present no token, but the
     * security configuration builds its decoder eagerly from the issuer's
     * discovery document. Pointing it at a real issuer keeps the configuration
     * under test identical to the one that runs in production, rather than
     * stubbing out the part most likely to be misconfigured.
     */
    private static final TestJwtIssuer JWT_ISSUER = TestJwtIssuer.start();
    private static final byte[] SYNTHETIC_PDF = "%PDF-1.4 synthetic test document, not a real file".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private DocumentUploadUseCase uploads;

    @Autowired
    private DocumentScanScheduler scanScheduler;

    @Autowired
    private ObjectStoragePort storage;

    @Autowired
    private S3Client s3;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PlatformContainers.postgres()::getJdbcUrl);
        registry.add("spring.datasource.username", PlatformContainers.postgres()::getUsername);
        registry.add("spring.datasource.password", PlatformContainers.postgres()::getPassword);
        registry.add("spring.kafka.bootstrap-servers", PlatformContainers.kafka()::getBootstrapServers);
        registry.add("spring.kafka.security.protocol", () -> "PLAINTEXT");

        registry.add("los.aws.region", () -> PlatformContainers.localstack().getRegion());
        registry.add(
                "los.aws.endpoint-override",
                () -> PlatformContainers.localstack().getEndpoint().toString());
        registry.add("los.documents.bucket", () -> BUCKET);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", JWT_ISSUER::issuerUri);
        registry.add("los.security.accepted-audiences", () -> "los-test-api");

        // The tests drive the scanner explicitly.
        registry.add("los.scanner.poll-interval", () -> "PT1H");
        registry.add("los.scanner.abandoned-sweep-interval", () -> "PT1H");
        registry.add("los.outbox.poll-interval", () -> "PT1H");
    }

    @BeforeEach
    void prepare() {
        jdbc.execute(
                """
                TRUNCATE TABLE document.outbox_event, document.processed_event, document.document
                RESTART IDENTITY CASCADE
                """);
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (RuntimeException alreadyExists) {
            // Created by an earlier test in this JVM. The containers are shared.
        }
    }

    @Test
    @DisplayName("a client with no AWS credentials can upload straight to S3 and the document is cleared")
    void happyPathUploadAndScan() throws Exception {
        String applicationId = UUID.randomUUID().toString();

        DocumentUploadUseCase.UploadTicket ticket = uploads.requestUpload(
                applicationId, DocumentType.PROOF_OF_INCOME, "application/pdf", (long) SYNTHETIC_PDF.length, "corr-1");

        // The client holds only the URL. No SDK, no credentials, no IAM role.
        int uploadStatus = putToPresignedUrl(ticket, SYNTHETIC_PDF, "application/pdf");
        assertThat(uploadStatus).isEqualTo(200);

        Document completed = uploads.completeUpload(ticket.documentId(), null, "corr-1");
        assertThat(completed.status()).isEqualTo(DocumentStatus.SCANNING);
        assertThat(completed.stored().sizeBytes()).isEqualTo(SYNTHETIC_PDF.length);

        scanScheduler.scanPendingDocuments();

        Document scanned = uploads.getById(ticket.documentId());
        assertThat(scanned.status()).isEqualTo(DocumentStatus.CLEAN);

        // The object left quarantine. That move is what lets a bucket policy deny
        // every principal except the scanner any read under quarantine/.
        ObjectLocation quarantine = ObjectLocation.quarantine(BUCKET, applicationId, ticket.documentId());
        assertThat(storage.describe(quarantine)).isEmpty();
        assertThat(storage.describe(quarantine.acceptedCounterpart())).isPresent();
    }

    @Test
    @DisplayName("the presigned URL only permits the content type it was issued for")
    void presignedUrlIsBoundToTheDeclaredContentType() throws Exception {
        // Without this binding, a URL issued for a PDF would happily accept an
        // executable and the content-type allow-list would be decorative.
        DocumentUploadUseCase.UploadTicket ticket = uploads.requestUpload(
                UUID.randomUUID().toString(), DocumentType.PROOF_OF_INCOME, "application/pdf", null, "corr-2");

        int status = putToPresignedUrl(ticket, SYNTHETIC_PDF, "application/octet-stream");

        // S3 refuses: the content type is part of the signature.
        assertThat(status).isEqualTo(403);
    }

    @Test
    @DisplayName("the presigned URL expires")
    void presignedUrlExpires() throws Exception {
        ObjectLocation location =
                ObjectLocation.quarantine(BUCKET, UUID.randomUUID().toString(), DocumentId.newId());
        ObjectStoragePort.PresignedUpload expired =
                storage.presignUpload(location, "application/pdf", Duration.ofSeconds(1));

        Thread.sleep(1500);

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(expired.url())
                        .header("Content-Type", "application/pdf")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(SYNTHETIC_PDF))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("completion is refused when nothing was actually uploaded")
    void completionWithoutAnUploadIsRefused() {
        // The client says it finished; S3 says the key is empty. Trusting the
        // client here would mark an application's document requirement satisfied
        // by a file that does not exist.
        DocumentUploadUseCase.UploadTicket ticket = uploads.requestUpload(
                UUID.randomUUID().toString(), DocumentType.PROOF_OF_IDENTITY, "application/pdf", null, "corr-3");

        assertThatThrownBy(() -> uploads.completeUpload(ticket.documentId(), null, "corr-3"))
                .isInstanceOf(UploadVerificationFailedException.class);

        assertThat(uploads.getById(ticket.documentId()).status()).isEqualTo(DocumentStatus.REJECTED);
    }

    @Test
    @DisplayName("an object larger than the limit is refused even though the URL permitted the write")
    void oversizedUploadIsRefusedOnCompletion() throws Exception {
        // The signature is not bound to a length, so S3 accepts the write. The
        // limit is enforced here, against what S3 actually stored -- which is
        // where it has to be enforced to mean anything.
        DocumentUploadUseCase.UploadTicket ticket = uploads.requestUpload(
                UUID.randomUUID().toString(), DocumentType.BANK_STATEMENT, "application/pdf", null, "corr-4");

        byte[] oversized = new byte[(int) UploadConstraints.MAXIMUM_SIZE_BYTES + 1024];
        assertThat(putToPresignedUrl(ticket, oversized, "application/pdf")).isEqualTo(200);

        assertThatThrownBy(() -> uploads.completeUpload(ticket.documentId(), null, "corr-4"))
                .isInstanceOf(UploadVerificationFailedException.class)
                .extracting(e -> ((UploadVerificationFailedException) e).errorCode())
                .isEqualTo(UploadConstraints.ERROR_FILE_TOO_LARGE);

        assertThat(uploads.getById(ticket.documentId()).status()).isEqualTo(DocumentStatus.REJECTED);
    }

    @Test
    @DisplayName("an empty object is refused")
    void emptyUploadIsRefused() throws Exception {
        DocumentUploadUseCase.UploadTicket ticket = uploads.requestUpload(
                UUID.randomUUID().toString(), DocumentType.PROOF_OF_ADDRESS, "application/pdf", null, "corr-5");

        assertThat(putToPresignedUrl(ticket, new byte[0], "application/pdf")).isEqualTo(200);

        assertThatThrownBy(() -> uploads.completeUpload(ticket.documentId(), null, "corr-5"))
                .isInstanceOf(UploadVerificationFailedException.class)
                .extracting(e -> ((UploadVerificationFailedException) e).errorCode())
                .isEqualTo(UploadConstraints.ERROR_FILE_EMPTY);
    }

    @Test
    @DisplayName("a size that does not match what was declared is refused")
    void sizeMismatchIsRefused() throws Exception {
        DocumentUploadUseCase.UploadTicket ticket = uploads.requestUpload(
                UUID.randomUUID().toString(), DocumentType.PROOF_OF_INCOME, "application/pdf", 100L, "corr-6");

        assertThat(putToPresignedUrl(ticket, SYNTHETIC_PDF, "application/pdf")).isEqualTo(200);

        assertThatThrownBy(() -> uploads.completeUpload(ticket.documentId(), null, "corr-6"))
                .isInstanceOf(UploadVerificationFailedException.class)
                .extracting(e -> ((UploadVerificationFailedException) e).errorCode())
                .isEqualTo(UploadConstraints.ERROR_SIZE_MISMATCH);
    }

    @Test
    @DisplayName("a document the scanner refuses is rejected and stays in quarantine")
    void infectedDocumentIsRejectedAndNotPromoted() throws Exception {
        // The simulator keys off a marker in the object key, which is derived
        // from the application identifier the test controls.
        String applicationId = "00000000-0000-4000-8000-0000infected1".replace("infected", "0000");
        DocumentUploadUseCase.UploadTicket ticket = uploadInfectedDocument();

        scanScheduler.scanPendingDocuments();

        Document scanned = uploads.getById(ticket.documentId());
        assertThat(scanned.status()).isEqualTo(DocumentStatus.REJECTED);

        // Never promoted: a refused object must not reach the accepted prefix.
        ObjectLocation quarantine = scanned.location();
        assertThat(storage.describe(quarantine)).isPresent();
        assertThat(storage.describe(quarantine.acceptedCounterpart())).isEmpty();
    }

    @Test
    @DisplayName("an upload whose URL expired without an object arriving is swept up")
    void abandonedUploadIsRejected() {
        DocumentUploadUseCase.UploadTicket ticket = uploads.requestUpload(
                UUID.randomUUID().toString(), DocumentType.PROOF_OF_INCOME, "application/pdf", null, "corr-7");

        // Force the expiry into the past rather than waiting for it.
        jdbc.update(
                "UPDATE document.document SET upload_expires_at = now() - interval '1 hour' WHERE id = ?",
                ticket.documentId().value());

        scanScheduler.sweepAbandonedUploads();

        assertThat(uploads.getById(ticket.documentId()).status()).isEqualTo(DocumentStatus.REJECTED);
        assertThat(uploads.getById(ticket.documentId()).scanReasonCode()).isEqualTo("UPLOAD_ABANDONED");
    }

    @Test
    @DisplayName("the object key never contains anything but identifiers")
    void objectKeysContainOnlyIdentifiers() {
        String applicationId = UUID.randomUUID().toString();
        DocumentUploadUseCase.UploadTicket ticket = uploads.requestUpload(
                applicationId, DocumentType.PROOF_OF_INCOME, "application/pdf", null, "corr-8");

        String objectKey = jdbc.queryForObject(
                "SELECT object_key FROM document.document WHERE id = ?",
                String.class,
                ticket.documentId().value());

        // Keys reach S3 access logs, CloudTrail, inventory reports and every
        // presigned URL ever issued, so anything identifying in one is permanent.
        assertThat(objectKey)
                .isEqualTo("quarantine/" + applicationId + "/" + ticket.documentId())
                .matches("^[A-Za-z0-9/_-]+$");
    }

    @Test
    @DisplayName("the upload event is written to the outbox but never carries the presigned URL")
    void publishedEventNeverCarriesTheCredential() {
        DocumentUploadUseCase.UploadTicket ticket = uploads.requestUpload(
                UUID.randomUUID().toString(), DocumentType.PROOF_OF_INCOME, "application/pdf", null, "corr-9");

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM document.outbox_event WHERE aggregate_id = ?",
                String.class,
                ticket.documentId().toString());

        assertThat(payload)
                .contains("document.upload-requested")
                .doesNotContain("X-Amz-Signature")
                .doesNotContain("X-Amz-Credential")
                .doesNotContain(ticket.upload().url().toString());
    }

    // --- helpers -------------------------------------------------------------

    /** Uploads a document whose key makes the simulated scanner refuse it. */
    private DocumentUploadUseCase.UploadTicket uploadInfectedDocument() throws Exception {
        // The simulator matches "infected" anywhere in the key. A UUID cannot
        // contain letters outside a-f, so the marker is placed by using an
        // application identifier the simulator will see -- here the document id
        // is generated, so the test instead marks the object by writing directly.
        DocumentUploadUseCase.UploadTicket ticket = uploads.requestUpload(
                UUID.randomUUID().toString(), DocumentType.PROOF_OF_INCOME, "application/pdf", null, "corr-infected");

        assertThat(putToPresignedUrl(ticket, SYNTHETIC_PDF, "application/pdf")).isEqualTo(200);
        uploads.completeUpload(ticket.documentId(), null, "corr-infected");

        // Rewrite the stored key so it carries the marker the simulator looks for.
        Document document = uploads.getById(ticket.documentId());
        String infectedKey = document.location().objectKey() + "-infected";
        s3.copyObject(b -> b.sourceBucket(BUCKET)
                .sourceKey(document.location().objectKey())
                .destinationBucket(BUCKET)
                .destinationKey(infectedKey));
        jdbc.update(
                "UPDATE document.document SET object_key = ? WHERE id = ?",
                infectedKey,
                ticket.documentId().value());

        return ticket;
    }

    private int putToPresignedUrl(DocumentUploadUseCase.UploadTicket ticket, byte[] body, String contentType)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(ticket.upload().url())
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
