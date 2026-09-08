package com.example.los.document.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.los.document.domain.model.Document;
import com.example.los.document.domain.model.DocumentId;
import com.example.los.document.observability.CorrelationId;
import com.example.los.document.usecase.service.DocumentUploadUseCase;
import com.example.los.events.vocabulary.DocumentType;

/**
 * The document API.
 *
 * <p>Three endpoints implementing the direct-to-S3 upload flow:
 *
 * <ol>
 *   <li>the client asks for an upload slot and receives a short-lived presigned
 *       URL plus the headers it must send;
 *   <li>the client {@code PUT}s the file <em>directly to S3</em> — that request
 *       never touches this service or API Gateway;
 *   <li>the client tells this service it is done, and the service verifies
 *       against what S3 actually holds before queueing a scan.
 * </ol>
 *
 * <p>There is deliberately no endpoint that accepts file content. Proxying
 * uploads would put every byte through a JVM heap and through API Gateway, whose
 * payload limit would then silently become the platform's maximum document size.
 */
@RestController
@RequestMapping("/v1/applications/{applicationId}/documents")
@Tag(name = "Documents")
class DocumentController {

    private final DocumentUploadUseCase uploads;

    DocumentController(DocumentUploadUseCase uploads) {
        this.uploads = uploads;
    }

    /**
     * Requests an upload slot.
     *
     * <p>The response carries a bearer credential, so it is marked
     * uncacheable — a presigned URL sitting in a shared cache is a write
     * credential handed to whoever queries that cache next.
     */
    @PostMapping("/upload-requests")
    @PreAuthorize("hasAuthority('SCOPE_documents:write')")
    @Operation(
            summary = "Request an upload slot",
            description = """
                    Returns a short-lived presigned `PUT` URL. The client uploads **directly \
                    to object storage** — the bytes never pass through this API.

                    There is deliberately no filename field. A client-supplied filename is \
                    user-controlled text that has to go somewhere, and every destination is \
                    wrong: in the object key it leaks through access logs and inventory \
                    reports, in the database it is personal data with no purpose, and in a \
                    response header it is a content-disposition injection.

                    **The response carries a bearer credential.** It is returned with \
                    `Cache-Control: no-store` — not merely `no-cache` — because a presigned \
                    URL sitting in a shared cache, a proxy, or a browser's disk cache is a \
                    write credential handed to whoever reads that cache next. Do not log it.
                    """)
    @ApiResponse(responseCode = "201", description = "An upload slot. Always `Cache-Control: no-store`.")
    @ApiResponse(responseCode = "400", description = "The request failed validation.", content = @Content)
    @ApiResponse(responseCode = "403", description = "The token lacks `documents:write`.", content = @Content)
    ResponseEntity<UploadTicketResponse> requestUpload(
            @PathVariable String applicationId, @Valid @RequestBody UploadRequest request) {

        DocumentUploadUseCase.UploadTicket ticket = uploads.requestUpload(
                applicationId,
                DocumentType.valueOf(request.documentType()),
                request.contentType(),
                request.sizeBytes(),
                CorrelationId.current());

        UploadTicketResponse body = new UploadTicketResponse(
                ticket.documentId().toString(),
                ticket.upload().url().toString(),
                ticket.upload().requiredHeaders(),
                Instant.now().plus(ticket.upload().expiresIn()));

        return ResponseEntity.status(HttpStatus.CREATED)
                // no-store, not merely no-cache: a presigned URL sitting in a
                // shared cache, a proxy, or a browser's disk cache is a write
                // credential handed to whoever reads that cache next.
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /**
     * Confirms that the upload finished.
     *
     * <p>The service does not take the client's word for it: it asks S3 what is
     * actually at the key and verifies that against what was declared.
     */
    @PostMapping("/{documentId}/complete")
    @PreAuthorize("hasAuthority('SCOPE_documents:write')")
    @Operation(
            summary = "Confirm that an upload finished",
            description = """
                    The service does not take the client's word for it. It asks object \
                    storage what is actually at the key and verifies size and content type \
                    against what was declared, then queues the object for scanning.

                    The document becomes usable only once it reaches `CLEAN`. Until then it \
                    sits in a quarantine prefix and cannot satisfy a mandatory requirement.
                    """)
    @ApiResponse(responseCode = "200", description = "The document's state after verification.")
    @ApiResponse(responseCode = "404", description = "No such document.", content = @Content)
    @ApiResponse(
            responseCode = "422",
            description = "What is at the key does not match what was declared, or the "
                    + "document is not awaiting an upload.",
            content = @Content)
    DocumentResponse completeUpload(
            @PathVariable String applicationId,
            @PathVariable String documentId,
            @Valid @RequestBody(required = false) CompleteUploadRequest request) {

        Document document = uploads.completeUpload(
                DocumentId.of(documentId),
                request == null ? null : request.checksumSha256(),
                CorrelationId.current());

        return DocumentResponse.from(document);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_documents:read')")
    @Operation(
            summary = "The documents attached to an application",
            description = "Each carries its scan status. Only `CLEAN` satisfies a mandatory requirement.")
    @ApiResponse(responseCode = "200", description = "The documents, with their scan status.")
    @ApiResponse(responseCode = "401", description = "No token, or a token that is not valid here.", content = @Content)
    @ApiResponse(responseCode = "403", description = "The token lacks `documents:read`.", content = @Content)
    List<DocumentResponse> listDocuments(@PathVariable String applicationId) {
        return uploads.listForApplication(applicationId).stream()
                .map(DocumentResponse::from)
                .toList();
    }

    // --- request and response types ------------------------------------------

    /**
     * A request for an upload slot.
     *
     * <p>There is no filename field. A client-supplied filename would be
     * user-controlled text that has to go somewhere, and every destination is
     * wrong: in the S3 key it leaks through access logs and inventory reports, in
     * the database it is personal data with no purpose, and in a response header
     * it is a content-disposition injection.
     *
     * @param documentType which category of supporting document this is
     * @param contentType  the type the client will upload; bound into the presigned signature
     * @param sizeBytes    optional declared size, verified against S3 on completion
     */
    record UploadRequest(
            @NotBlank(message = "documentType is required")
            @Pattern(
                    regexp = "^(PROOF_OF_IDENTITY|PROOF_OF_INCOME|PROOF_OF_ADDRESS|BANK_STATEMENT)$",
                    message = "documentType is not a recognised document category")
            String documentType,

            @NotBlank(message = "contentType is required")
            @Schema(
                            description = "The presigned URL is BOUND to this value. An upload whose "
                                    + "Content-Type differs is refused by object storage.",
                            example = "application/pdf")
            String contentType,

            @Positive(message = "sizeBytes must be positive")
            @Schema(description = "Declared size. Verified against what object storage actually holds.")
            Long sizeBytes) {}

    /**
     * Optional completion details.
     *
     * @param checksumSha256 base64 SHA-256 the client computed. When supplied it
     *                       is compared with the checksum S3 computed, which gives
     *                       end-to-end integrity rather than the client's word
     */
    record CompleteUploadRequest(
            @Schema(
                            description = "Optional. When supplied it is verified against what object "
                                    + "storage actually holds.",
                            pattern = "^[A-Fa-f0-9]{64}$")
                    String checksumSha256) {}

    /**
     * The upload slot.
     *
     * <p>Contains a bearer credential. It is returned once, to the caller that
     * asked for it, and is never logged, stored or published.
     *
     * @param uploadUrl       presigned PUT URL
     * @param requiredHeaders headers the client must send for the signature to match
     * @param expiresAt       when the URL stops working
     */
    record UploadTicketResponse(
            @Schema(format = "uuid") String documentId,
            @Schema(
                            description = """
                            **A bearer credential.** Anyone holding this URL can write the object \
                            until it expires.

                            Never log it, never cache it, never put it in a URL shortener or an \
                            analytics payload. The response is returned with `Cache-Control: \
                            no-store` for the same reason.
                            """,
                            format = "uri")
                    String uploadUrl,
            @Schema(
                            description = "Headers that must be sent verbatim on the PUT. The signature "
                                    + "covers them, so the upload is refused if any differs.")
                    Map<String, String> requiredHeaders,
            @Schema(description = "Deliberately short-lived.") Instant expiresAt) {

        public UploadTicketResponse {
            requiredHeaders = Map.copyOf(requiredHeaders);
        }

        /** Redacted: the URL is a write credential. */
        @Override
        public String toString() {
            return "UploadTicketResponse[documentId=" + documentId + ", uploadUrl=redacted, expiresAt=" + expiresAt
                    + "]";
        }
    }

    /**
     * A document's metadata.
     *
     * <p>Deliberately excludes the bucket and the object key. A caller has no use
     * for either — they cannot read the object directly, by design — and exposing
     * the key would tell an attacker exactly what to try to reach.
     */
    record DocumentResponse(
            @Schema(format = "uuid") String documentId,
            @Schema(format = "uuid") String applicationId,
            @Schema(
                            description = "PROOF_OF_IDENTITY and PROOF_OF_INCOME are mandatory: an "
                                    + "application cannot be submitted until both have reached CLEAN.",
                            allowableValues = {
                                "PROOF_OF_IDENTITY",
                                "PROOF_OF_INCOME",
                                "PROOF_OF_ADDRESS",
                                "BANK_STATEMENT"
                            })
                    String documentType,
            @Schema(
                            description = "Only CLEAN satisfies a mandatory requirement. Until then the "
                                    + "object sits in a quarantine prefix and is never served.",
                            allowableValues = {
                                "PENDING_UPLOAD",
                                "UPLOADED",
                                "SCANNING",
                                "CLEAN",
                                "REJECTED"
                            })
                    String status,
            Long sizeBytes,
            String reasonCode,
            Instant createdAt,
            Instant updatedAt) {

        static DocumentResponse from(Document document) {
            return new DocumentResponse(
                    document.id().toString(),
                    document.applicationId(),
                    document.type().name(),
                    document.status().name(),
                    document.stored() == null ? null : document.stored().sizeBytes(),
                    document.scanReasonCode(),
                    document.createdAt(),
                    document.updatedAt());
        }
    }
}
