package com.example.los.document.domain.model;

import java.util.Set;

/**
 * What the platform will accept as a supporting document.
 *
 * <p>The constraints are checked twice, and both times matter for different
 * reasons:
 *
 * <ul>
 *   <li><b>When the upload is requested</b>, against what the client declares.
 *       This gives the caller a fast, useful error before they transfer
 *       anything.
 *   <li><b>When the upload completes</b>, against what S3 actually reports. This
 *       is the one that provides security. A presigned URL is a bearer
 *       credential to write one key: whoever holds it can put any bytes there,
 *       of any size and any type. Validating only the request that asked for the
 *       URL makes both limits decorative.
 * </ul>
 */
public final class UploadConstraints {

    /** 20 MB. Large enough for a scanned multi-page statement, small enough to bound cost and scan time. */
    public static final long MAXIMUM_SIZE_BYTES = 20L * 1024 * 1024;

    /** An empty object is never a valid document and is usually a failed upload. */
    public static final long MINIMUM_SIZE_BYTES = 1L;

    /**
     * Content types the platform accepts.
     *
     * <p>An allow-list, never a deny-list. A deny-list is a promise to have
     * thought of every dangerous type in advance, which nobody can keep.
     * Deliberately excludes anything active: no HTML, no SVG (which can carry
     * script), no archives (which hide their contents from a scanner), no Office
     * formats with macros.
     */
    public static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("application/pdf", "image/jpeg", "image/png", "image/tiff");

    public static final String ERROR_CONTENT_TYPE_NOT_ALLOWED = "CONTENT_TYPE_NOT_ALLOWED";
    public static final String ERROR_FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String ERROR_FILE_EMPTY = "FILE_EMPTY";
    public static final String ERROR_CONTENT_TYPE_MISMATCH = "CONTENT_TYPE_MISMATCH";
    public static final String ERROR_SIZE_MISMATCH = "SIZE_MISMATCH";
    public static final String ERROR_CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH";

    private UploadConstraints() {}

    /** Validates the content type a client says it will upload. */
    public static void validateContentType(String contentType) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(java.util.Locale.ROOT))) {
            throw new UploadVerificationFailedException(
                    ERROR_CONTENT_TYPE_NOT_ALLOWED,
                    "This content type is not accepted. Permitted types: " + sortedAllowedTypes());
        }
    }

    /** Validates the size a client says it will upload, when it declares one. */
    public static void validateDeclaredSize(Long declaredSizeBytes) {
        if (declaredSizeBytes == null) {
            return;
        }
        if (declaredSizeBytes > MAXIMUM_SIZE_BYTES) {
            throw new UploadVerificationFailedException(
                    ERROR_FILE_TOO_LARGE, "The document exceeds the maximum size of " + MAXIMUM_SIZE_BYTES + " bytes");
        }
        if (declaredSizeBytes < MINIMUM_SIZE_BYTES) {
            throw new UploadVerificationFailedException(ERROR_FILE_EMPTY, "The document is empty");
        }
    }

    /**
     * Verifies the object S3 actually stored against what the client declared.
     *
     * <p>This is the check that counts. Everything before it is advisory.
     *
     * @throws UploadVerificationFailedException on the first constraint violated
     */
    public static void verifyStoredObject(StoredObject stored, String declaredContentType, Long declaredSizeBytes) {
        if (stored.sizeBytes() > MAXIMUM_SIZE_BYTES) {
            throw new UploadVerificationFailedException(
                    ERROR_FILE_TOO_LARGE,
                    "The uploaded object exceeds the maximum size of " + MAXIMUM_SIZE_BYTES + " bytes");
        }
        if (stored.sizeBytes() < MINIMUM_SIZE_BYTES) {
            throw new UploadVerificationFailedException(ERROR_FILE_EMPTY, "The uploaded object is empty");
        }

        String storedType = normalise(stored.contentType());
        if (!ALLOWED_CONTENT_TYPES.contains(storedType)) {
            throw new UploadVerificationFailedException(
                    ERROR_CONTENT_TYPE_NOT_ALLOWED,
                    "The uploaded object has a content type that is not accepted. Permitted types: "
                            + sortedAllowedTypes());
        }
        if (!storedType.equals(normalise(declaredContentType))) {
            // Declared one thing, uploaded another. Refused even though the
            // uploaded type is itself allowed: the mismatch is the signal.
            throw new UploadVerificationFailedException(
                    ERROR_CONTENT_TYPE_MISMATCH,
                    "The uploaded object does not have the content type that was declared when the upload "
                            + "was requested");
        }

        if (declaredSizeBytes != null && declaredSizeBytes != stored.sizeBytes()) {
            throw new UploadVerificationFailedException(
                    ERROR_SIZE_MISMATCH,
                    "The uploaded object is not the size that was declared when the upload was requested");
        }
    }

    /**
     * Compares the checksum S3 computed against one the client supplied.
     *
     * <p>Separate from {@link #verifyStoredObject} because a checksum is optional:
     * a client that supplies one gets end-to-end integrity, and one that does not
     * still gets the size and type checks.
     */
    public static void verifyChecksum(StoredObject stored, String expectedSha256) {
        if (expectedSha256 == null || stored.checksumSha256() == null) {
            return;
        }
        if (!expectedSha256.equalsIgnoreCase(stored.checksumSha256())) {
            throw new UploadVerificationFailedException(
                    ERROR_CHECKSUM_MISMATCH,
                    "The uploaded object does not match the checksum supplied when the upload was requested");
        }
    }

    /**
     * A content type without its parameters, lower-cased.
     *
     * <p>{@code application/pdf; charset=binary} and {@code APPLICATION/PDF} are
     * the same type, and a client library may send either.
     */
    private static String normalise(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameterStart = contentType.indexOf(';');
        String base = parameterStart < 0 ? contentType : contentType.substring(0, parameterStart);
        return base.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static String sortedAllowedTypes() {
        return String.join(", ", ALLOWED_CONTENT_TYPES.stream().sorted().toList());
    }
}
