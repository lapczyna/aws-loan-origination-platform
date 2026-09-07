package com.example.los.audit.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Web security.
 *
 * <p>The service is an OAuth2 resource server. It validates JWTs issued by an
 * external identity provider and never issues, stores or refreshes a credential
 * of its own.
 *
 * <h2>Audience validation</h2>
 *
 * <p>Signature and expiry alone are not enough. A token issued by the same
 * provider for a different application is correctly signed and unexpired, so
 * without an audience check any client of that provider could call this API with
 * a token it obtained legitimately for something else. The audience validator
 * below is what makes the token specific to this API.
 *
 * <h2>CORS</h2>
 *
 * <p>Restrictive by default: no origins are permitted unless configured. A
 * wildcard here would let any website in the world make authenticated requests
 * from a victim's browser.
 *
 * <h2>What is public</h2>
 *
 * <p>Only the liveness and readiness probes. Every other actuator endpoint, the
 * metrics endpoint included, requires authentication: metrics leak volumes,
 * error rates and internal structure.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        return http
                // No cookies, no sessions, no CSRF token: this is a token-authenticated
                // API. CSRF defends against a browser attaching an ambient credential
                // automatically, which cannot happen when the credential is a bearer
                // token the caller must set explicitly.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Kubernetes probes. Reachable without a token because the
                        // kubelet has none, and they expose no business information.
                        .requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        // HSTS is set here as well as at the edge: defence in depth
                        // costs nothing and survives an edge misconfiguration.
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
                .build();
    }

    /**
     * Decoder validating signature, expiry, issuer and audience.
     *
     * @param issuerUri the identity provider's issuer URI; a placeholder by
     *                  default, never a real tenant, so no environment identifier
     *                  is committed to this repository
     * @param audiences the audience values this API accepts
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${los.security.accepted-audiences}") List<String> audiences) {

        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud",
                claim -> claim != null && claim.stream().anyMatch(audiences::contains));

        decoder.setJwtValidator(
                new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuerUri), audienceValidator));

        return decoder;
    }

    /**
     * CORS policy.
     *
     * <p>Allowed origins come from configuration and default to none. Credentials
     * are permitted only when explicit origins are configured, because
     * {@code Access-Control-Allow-Credentials} with a wildcard origin is both
     * forbidden by the specification and a serious vulnerability.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${los.security.cors.allowed-origins:}") List<String> allowedOrigins) {

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT"));
        configuration.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id", "If-Match"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "ETag", "Idempotent-Replay"));
        configuration.setAllowCredentials(!allowedOrigins.isEmpty());
        configuration.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", configuration);
        return source;
    }
}
