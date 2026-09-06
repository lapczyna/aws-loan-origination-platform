package com.example.los.application.adapter.in.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.example.los.application.usecase.port.IdempotencyConflict;
import com.example.los.application.usecase.port.IdempotencyStore;

/**
 * Runs a state-changing request at most once per idempotency key.
 *
 * <h2>The contract</h2>
 *
 * <ul>
 *   <li><b>New key.</b> The operation runs and its response is recorded.
 *   <li><b>Same key, same body.</b> The recorded response is replayed verbatim.
 *       The operation does not run again.
 *   <li><b>Same key, different body.</b> 409 Conflict. Replaying the first
 *       response would silently discard the second request, which is worse than
 *       an error, and running it would break the guarantee the key promised.
 * </ul>
 *
 * <h2>Why the fingerprint is over canonical JSON</h2>
 *
 * <p>A byte-for-byte hash of the raw body would report a conflict for two
 * genuinely identical requests that differ only in key order or whitespace,
 * which is exactly what happens when a client retries through a different HTTP
 * library. Hashing the parsed and re-serialised form compares meaning rather
 * than formatting.
 *
 * <h2>Why the record is written inside the caller's transaction</h2>
 *
 * <p>{@link IdempotencyStore#store} demands an existing transaction, so the
 * business effect and the record of it commit together. Written afterwards in
 * its own transaction, a crash in between would leave an effect with no record —
 * and the client's retry would apply it a second time.
 */
@Component
public class IdempotentRequestExecutor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentRequestExecutor.class);

    /** Header carrying the client's key, as used by Stripe, PayPal and the IETF draft. */
    public static final String HEADER = "Idempotency-Key";

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotentRequestExecutor(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes {@code operation} unless this key has already been used.
     *
     * @param idempotencyKey the client's key; when absent the operation simply runs
     * @param operationName  namespaces the key, so one key may be used once per operation
     * @param requestBody    the request, hashed to detect reuse with different content
     * @param successStatus  status to return on first execution
     * @param operation      the work to perform; must run in the caller's transaction
     * @param <T>            response body type
     */
    @Transactional
    public <T> ResponseEntity<Object> execute(
            String idempotencyKey,
            String operationName,
            Object requestBody,
            HttpStatus successStatus,
            Supplier<IdempotentResult<T>> operation) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // Idempotency is opt-in. A client that does not send a key accepts
            // that a retry may repeat the effect; the API does not invent a key
            // on their behalf, because a key derived from the body would silently
            // suppress a legitimate identical second request.
            IdempotentResult<T> result = operation.get();
            return ResponseEntity.status(successStatus).body(result.body());
        }

        String fingerprint = fingerprintOf(requestBody);

        Optional<IdempotencyStore.StoredResponse> existing = store.find(idempotencyKey, operationName);
        if (existing.isPresent()) {
            IdempotencyStore.StoredResponse stored = existing.get();

            if (!MessageDigest.isEqual(
                    stored.requestFingerprint().getBytes(StandardCharsets.UTF_8),
                    fingerprint.getBytes(StandardCharsets.UTF_8))) {
                throw new IdempotencyConflict();
            }

            log.info("Replaying stored response for a repeated request. operation={}", operationName);
            return ResponseEntity.status(stored.responseStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotent-Replay", "true")
                    // Parsed back into a tree rather than returned as a String, so
                    // the JSON is written as JSON. A String body would be handled
                    // by whichever message converter happened to be ordered first
                    // and could be emitted as a quoted JSON string.
                    .body(objectMapper.readTree(stored.responseBody()));
        }

        IdempotentResult<T> result = operation.get();
        String serialisedBody = objectMapper.writeValueAsString(result.body());

        // Throws if a concurrent request claimed the key first, which rolls this
        // whole transaction back including the effect above.
        store.store(
                idempotencyKey,
                operationName,
                fingerprint,
                result.resourceId(),
                successStatus.value(),
                serialisedBody);

        return ResponseEntity.status(successStatus).body(result.body());
    }

    /**
     * The outcome of an idempotent operation.
     *
     * @param resourceId identifier of the affected application, stored alongside
     *                   the key so support can answer "what did this key do?"
     * @param body       the response body
     */
    public record IdempotentResult<T>(String resourceId, T body) {}

    private String fingerprintOf(Object requestBody) {
        try {
            // Canonical form: Jackson serialises record components in declaration
            // order, so two semantically identical bodies produce identical bytes
            // regardless of how the client formatted them.
            byte[] canonical = objectMapper.writeValueAsBytes(requestBody);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
