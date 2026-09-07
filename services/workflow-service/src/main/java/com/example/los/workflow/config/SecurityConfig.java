package com.example.los.workflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Web security for the workflow service.
 *
 * <p>This service has no public API. It is driven entirely by Kafka events and a
 * scheduler, and it exposes only health probes and metrics. Everything except the
 * two Kubernetes probes therefore requires authentication — including metrics,
 * which reveal assessment volumes, failure rates and provider health.
 *
 * <p>It is also placed on an internal ClusterIP service with a NetworkPolicy that
 * admits no ingress traffic from outside the namespace, so nothing here is
 * reachable from API Gateway at all.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Reachable without a token because the kubelet has none.
                        .requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny()))
                .build();
    }
}
