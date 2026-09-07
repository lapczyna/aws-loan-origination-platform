package com.example.los.document.config;

import java.net.URI;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 client and presigner.
 *
 * <h2>Credentials</h2>
 *
 * <p>In AWS the default credentials provider chain resolves the pod's IAM role
 * through EKS Pod Identity. There is no access key anywhere: not in
 * configuration, not in an environment variable, not in the image. Against
 * LocalStack the conventional dummy credentials are used, and they grant access
 * to nothing outside a local container.
 *
 * <h2>Why the presigner can use a different endpoint from the client</h2>
 *
 * <p>The service and the client reach S3 by different routes, and the presigned
 * URL has to be signed for the route the CLIENT will take. The host is part of
 * the SigV4 signature, so a URL signed for one hostname and sent to another is
 * rejected.
 *
 * <p>Locally the service reaches LocalStack at {@code localstack:4566} on the
 * Docker network while a browser reaches it at {@code localhost:4566}. The same
 * split exists in AWS whenever the service egresses through an S3 VPC endpoint
 * while clients use the public endpoint. One override for the client and another
 * for the presigner is therefore not a local workaround: it is the shape the
 * problem actually has.
 *
 * <p>The presigner override defaults to the client's, so an environment where
 * both routes are the same needs no extra configuration.
 *
 * <h2>Path-style addressing</h2>
 *
 * <p>Forced on only when an endpoint override is configured, which in practice
 * means LocalStack. Real S3 uses virtual-host addressing
 * ({@code bucket.s3.region.amazonaws.com}), and forcing path style against it is
 * deprecated. LocalStack, by contrast, does not resolve per-bucket hostnames, so
 * a presigned URL generated in virtual-host style points at a host that does not
 * exist and the upload fails with a DNS error that looks nothing like the real
 * cause. Deriving this from the endpoint override rather than a separate flag
 * means the two settings cannot drift apart.
 */
@Configuration
class S3Config {

    private static final Logger log = LoggerFactory.getLogger(S3Config.class);

    @Bean
    S3Client s3Client(
            @Value("${los.aws.region}") String region,
            @Value("${los.aws.endpoint-override:}") String endpointOverride) {

        var builder = S3Client.builder()
                .region(Region.of(region))
                // The URL-connection client rather than Netty or Apache: this
                // service makes a handful of small metadata calls, and the
                // lighter client keeps the image and the startup time smaller.
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(hasEndpointOverride(endpointOverride))
                        .build());

        applyEndpointAndCredentials(builder::endpointOverride, builder::credentialsProvider, endpointOverride);
        return builder.build();
    }

    @Bean
    S3Presigner s3Presigner(
            @Value("${los.aws.region}") String region,
            @Value("${los.aws.endpoint-override:}") String endpointOverride,
            @Value("${los.aws.presign-endpoint-override:}") String presignEndpointOverride) {

        // Falls back to the client's endpoint when no separate one is set, so
        // an environment where both routes are identical needs no extra config.
        String effectiveEndpoint =
                hasEndpointOverride(presignEndpointOverride) ? presignEndpointOverride : endpointOverride;

        var builder = S3Presigner.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(hasEndpointOverride(effectiveEndpoint))
                        .build());

        applyEndpointAndCredentials(builder::endpointOverride, builder::credentialsProvider, effectiveEndpoint);
        return builder.build();
    }

    /**
     * Applies the endpoint override and picks a credentials provider.
     *
     * <p>Shared by both beans so the client and the presigner cannot end up
     * pointing at different endpoints — a mismatch that produces presigned URLs
     * for a host the service itself never talks to, and is invisible until an
     * upload silently fails.
     */
    private static void applyEndpointAndCredentials(
            java.util.function.Consumer<URI> endpointSetter,
            java.util.function.Consumer<software.amazon.awssdk.auth.credentials.AwsCredentialsProvider>
                            credentialsSetter,
            String endpointOverride) {

        Optional<URI> endpoint = hasEndpointOverride(endpointOverride)
                ? Optional.of(URI.create(endpointOverride))
                : Optional.empty();

        endpoint.ifPresentOrElse(
                uri -> {
                    endpointSetter.accept(uri);
                    // LocalStack accepts any credentials. These are the
                    // conventional dummy values and are not a secret: they grant
                    // access to nothing beyond a container on this machine.
                    credentialsSetter.accept(
                            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
                    log.warn(
                            "S3 is configured against an endpoint override with path-style addressing. "
                                    + "This is the local development path and must never be set in AWS.");
                },
                // No override: resolve the pod's IAM role through the default
                // chain. Nothing static, nothing configured, nothing to leak.
                () -> credentialsSetter.accept(DefaultCredentialsProvider.create()));
    }

    private static boolean hasEndpointOverride(String endpointOverride) {
        return endpointOverride != null && !endpointOverride.isBlank();
    }
}
