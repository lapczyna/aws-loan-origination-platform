package com.example.los.application.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.OAuthFlow;
import io.swagger.v3.oas.annotations.security.OAuthFlows;
import io.swagger.v3.oas.annotations.security.OAuthScope;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document's identity, declared next to the code it describes.
 *
 * <p><b>Why this is here rather than in a hand-written YAML file.</b> A spec
 * maintained separately from the code drifts, and it drifts silently: nothing
 * fails when a field is renamed, so the document stays plausible and becomes
 * wrong. Generating it from the controllers, the DTOs and their bean-validation
 * annotations means the published constraints are the constraints actually
 * enforced. {@code OpenApiContractIT} then fails the build if the committed
 * document no longer matches what the services produce.
 *
 * <p>This service is one of two that serve the public API surface; the document
 * service serves the document paths. The committed document is the union of the
 * two, and the info, servers and security scheme below are the ones it carries.
 *
 * <p>The {@code /v3/api-docs} endpoint itself is <b>not</b> public: the security
 * filter chain requires a token for everything except the Kubernetes probes. An
 * unauthenticated API-docs endpoint enumerates the entire attack surface for
 * whoever asks.
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Loan Origination Platform API",
                        version = "1.0.0",
                        summary = "Create, document, submit and track loan applications.",
                        description =
                                """
                        ## What this is

                        A **reference implementation**. It has never been deployed, it has \
                        never processed real data, and every external check behind it — KYC, \
                        AML, fraud, credit scoring, malware scanning — is a **simulation** \
                        that contacts no real provider.

                        ## Two services, one API surface

                        These paths are served by two independently deployed services behind \
                        a single API Gateway. `/v1/applications/{applicationId}/documents/**` \
                        belongs to the document service; everything else belongs to the \
                        application service. Callers should not depend on that split — it is \
                        recorded because it explains why a document can be accepted a moment \
                        before submission will accept it.

                        ## Idempotency

                        `POST /v1/applications` and `POST .../submit` accept an \
                        `Idempotency-Key` header, and submission is the one that most needs \
                        it: a client that times out and retries must not submit twice.

                        | Situation | Result |
                        |---|---|
                        | Same key, same request | The original response, with `Idempotent-Replay: true` |
                        | Same key, different request | `409` with `IDEMPOTENCY_KEY_REUSED` |
                        | Same key, original still in flight | `409` with `IDEMPOTENCY_KEY_IN_USE` |

                        Requests are compared by a SHA-256 fingerprint of the \
                        **canonicalised** body, so a client retrying through a different HTTP \
                        library — different whitespace, different key order — is not treated \
                        as a different request.

                        ## Errors

                        Every error is an RFC 9457 Problem Detail carrying a stable, \
                        machine-readable `errorCode`. Error responses never contain a stack \
                        trace, a SQL fragment, a table name, an internal identifier, or an \
                        exception message. Validation errors name the **field** and never \
                        echo its value, because the value is frequently the personal data the \
                        platform exists to protect.

                        Branch on `errorCode`, never on `detail`, which is prose and may be \
                        reworded.

                        ## Personal data

                        The API never returns an applicant's name, email address or date of \
                        birth — not even to the client that supplied them. An applicant is \
                        identified by `applicantReference`, an irreversible HMAC pseudonym, \
                        plus initials.

                        That reference is **pseudonymised, not anonymised**: the same \
                        applicant always produces the same value, so it remains personal data \
                        under GDPR.
                        """,
                        contact = @Contact(name = "Repository", url = "https://example.com/aws-loan-origination-platform"),
                        license = @License(name = "Apache-2.0", identifier = "Apache-2.0")),
        servers =
                @Server(
                        url = "https://api.example.com",
                        description =
                                "PLACEHOLDER. No deployment exists. A real deployment sits behind API Gateway, "
                                        + "which is regional, WAF-protected, and reachable only over TLS."),
        security = @SecurityRequirement(name = "oauth2"),
        tags = {
            @Tag(name = "Applications", description = "Creating, submitting and tracking a loan application."),
            @Tag(name = "Documents", description = "Uploading supporting documents through short-lived presigned URLs."),
            @Tag(name = "Manual review", description = "The queue a human works, and the decisions they record.")
        })
@SecurityScheme(
        name = "oauth2",
        type = SecuritySchemeType.OAUTH2,
        description =
                """
                An OAuth2 bearer token, validated at API Gateway **and again by each \
                service**. The second validation is what protects the services if anything \
                is ever deployed with a route that bypasses the gateway.

                Partners additionally present an API key. The key is what a usage plan \
                throttles on — a capacity identity, never an authentication one — so a key \
                alone grants nothing.
                """,
        flows =
                @OAuthFlows(
                        clientCredentials =
                                @OAuthFlow(
                                        tokenUrl = "https://auth.example.com/oauth2/token",
                                        scopes = {
                                            @OAuthScope(
                                                    name = "applications:read",
                                                    description = "Read applications and their status"),
                                            @OAuthScope(
                                                    name = "applications:write",
                                                    description = "Create, update and submit applications"),
                                            @OAuthScope(
                                                    name = "documents:read",
                                                    description = "List documents on an application"),
                                            @OAuthScope(
                                                    name = "documents:write",
                                                    description = "Request upload slots and confirm uploads"),
                                            @OAuthScope(
                                                    name = "manual-review:read",
                                                    description = "Read the manual review queue"),
                                            @OAuthScope(
                                                    name = "manual-review:decide",
                                                    description = "Record a reviewer's decision")
                                        })))
class OpenApiDocumentation {}
