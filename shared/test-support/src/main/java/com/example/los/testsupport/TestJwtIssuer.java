package com.example.los.testsupport;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * A throwaway OpenID Connect issuer for integration tests.
 *
 * <p>Generates an RSA key pair in memory, serves the OIDC discovery document and
 * the JWKS over HTTP, and mints signed tokens on demand.
 *
 * <p><strong>Why a real issuer rather than a stubbed decoder.</strong> Replacing
 * the {@code JwtDecoder} bean with something that trusts any token is the easy
 * option, and it makes the security configuration untested: audience validation,
 * issuer validation and expiry checking are exactly the parts most likely to be
 * misconfigured, and a stub verifies none of them. Standing up a real issuer
 * means the tests exercise the same code path production does.
 *
 * <p><strong>Why the key is generated per run.</strong> No private key is
 * committed to this repository, not even a test one. A key in version control is
 * a key that ends up copied into somewhere it matters, and a public repository
 * with a private key in its history is a finding regardless of what the key was
 * for.
 */
public final class TestJwtIssuer implements AutoCloseable {

    private static final int KEY_SIZE_BITS = 2048;

    private final WireMockServer server;
    private final RSAKey signingKey;
    private final String issuerUri;

    private TestJwtIssuer(WireMockServer server, RSAKey signingKey, String issuerUri) {
        this.server = server;
        this.signingKey = signingKey;
        this.issuerUri = issuerUri;
    }

    /** Starts an issuer on a random free port. */
    public static TestJwtIssuer start() {
        KeyPair keyPair = generateKeyPair();
        RSAKey signingKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();

        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        String issuerUri = "http://localhost:" + server.port();

        // Spring's JwtDecoders.fromIssuerLocation performs OIDC discovery, so
        // both documents have to be served for the production code path to work.
        server.stubFor(get(urlEqualTo("/.well-known/openid-configuration"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(discoveryDocument(issuerUri))));

        server.stubFor(get(urlEqualTo("/jwks"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(new JWKSet(signingKey.toPublicJWK()).toString())));

        return new TestJwtIssuer(server, signingKey, issuerUri);
    }

    /** The issuer URI to configure as {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}. */
    public String issuerUri() {
        return issuerUri;
    }

    /**
     * Mints a valid token.
     *
     * @param subject  the {@code sub} claim; used as the reviewer reference for
     *                 manual-review decisions
     * @param audience the {@code aud} claim, which the resource server validates
     * @param scopes   OAuth2 scopes, mapped by Spring Security to SCOPE_ authorities
     */
    public String mintToken(String subject, String audience, String... scopes) {
        return mintToken(subject, audience, Instant.now().plus(30, ChronoUnit.MINUTES), scopes);
    }

    /** Mints a token that has already expired, for testing rejection of stale credentials. */
    public String mintExpiredToken(String subject, String audience, String... scopes) {
        return mintToken(subject, audience, Instant.now().minus(1, ChronoUnit.MINUTES), scopes);
    }

    private String mintToken(String subject, String audience, Instant expiresAt, String... scopes) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuerUri)
                .subject(subject)
                .audience(List.of(audience))
                .jwtID(UUID.randomUUID().toString())
                .issueTime(java.util.Date.from(now.minusSeconds(5)))
                .expirationTime(java.util.Date.from(expiresAt))
                .claim("scope", String.join(" ", scopes))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(signingKey.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(signingKey.toPrivateKey()));
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Could not sign the test token", e);
        }
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop();
    }

    private static String discoveryDocument(String issuerUri) {
        return """
                {
                  "issuer": "%s",
                  "jwks_uri": "%s/jwks",
                  "authorization_endpoint": "%s/authorize",
                  "token_endpoint": "%s/token",
                  "response_types_supported": ["code"],
                  "subject_types_supported": ["public"],
                  "id_token_signing_alg_values_supported": ["RS256"]
                }
                """
                .formatted(issuerUri, issuerUri, issuerUri, issuerUri);
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE_BITS);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is not available in this JVM", e);
        }
    }
}
