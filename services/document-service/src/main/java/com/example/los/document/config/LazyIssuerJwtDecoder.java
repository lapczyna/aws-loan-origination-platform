package com.example.los.document.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * A {@link JwtDecoder} that discovers the issuer's configuration on first use
 * rather than at startup.
 *
 * <h2>Why this is not eager</h2>
 *
 * <p>{@link JwtDecoders#fromIssuerLocation} performs an HTTP call to the
 * identity provider's discovery document while the Spring context is being
 * built. If the provider is briefly unreachable at that moment, the bean fails,
 * the context fails, and the service exits.
 *
 * <p>That turns a transient dependency blip into an outage of this service, and
 * it does so at exactly the worst time: during a rolling deployment, when every
 * new pod is starting at once and any wobble in the identity provider stops the
 * whole rollout. A service that is already running is unaffected by the same
 * blip, which makes the failure mode arbitrary — it depends only on whether a
 * pod happened to be restarting.
 *
 * <p>Deferring discovery to the first token that needs validating means the
 * service starts, reports itself live, and fails individual requests with 401
 * while the provider is unavailable — recovering on its own when it returns. It
 * also removes a startup ordering constraint locally, where the identity
 * provider is a container that may still be booting.
 *
 * <h2>Why the result is cached</h2>
 *
 * <p>Discovery and the JWKS fetch are expensive relative to validating a token.
 * The delegate is built once and reused; concurrent first requests may briefly
 * build it more than once, which is harmless and cheaper than holding a lock on
 * the request path.
 *
 * <h2>Audience validation</h2>
 *
 * <p>Signature and expiry alone are not enough. A token minted by the same
 * provider for a different application is correctly signed and unexpired, so
 * without an audience check any client of that provider could call this API with
 * a token it obtained legitimately for something else.
 */
class LazyIssuerJwtDecoder implements JwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(LazyIssuerJwtDecoder.class);

    private final String issuerUri;
    private final List<String> acceptedAudiences;
    private final AtomicReference<JwtDecoder> delegate = new AtomicReference<>();

    LazyIssuerJwtDecoder(String issuerUri, List<String> acceptedAudiences) {
        this.issuerUri = issuerUri;
        this.acceptedAudiences = List.copyOf(acceptedAudiences);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        return resolveDelegate().decode(token);
    }

    private JwtDecoder resolveDelegate() {
        JwtDecoder existing = delegate.get();
        if (existing != null) {
            return existing;
        }

        JwtDecoder created = build();
        // compareAndSet rather than set: a concurrent caller may have won, and
        // reusing its instance keeps a single JWKS cache rather than two.
        return delegate.compareAndSet(null, created) ? created : delegate.get();
    }

    private JwtDecoder build() {
        log.info("Discovering OpenID configuration for the configured issuer");

        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", claim -> claim != null && claim.stream().anyMatch(acceptedAudiences::contains));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri), audienceValidator));

        log.info("OpenID configuration resolved; token validation is active");
        return decoder;
    }
}
