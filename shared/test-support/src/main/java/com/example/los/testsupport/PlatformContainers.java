package com.example.los.testsupport;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The container images every integration test runs against.
 *
 * <p>Declared once, in one place, for three reasons.
 *
 * <p><b>Pinned tags.</b> Every image is pinned to an exact version. A floating
 * {@code latest} makes the test suite non-reproducible: a green build today and
 * a red one tomorrow from identical source, caused by an image that changed
 * underneath it.
 *
 * <p><b>Shared instances.</b> The containers are static and reused across every
 * test class in a JVM. Testcontainers' Singleton pattern is used rather than
 * JUnit's {@code @Container} lifecycle because starting PostgreSQL, Kafka and
 * LocalStack per class turns a two-minute suite into a twenty-minute one. Tests
 * isolate themselves by cleaning their own data, not by getting a fresh
 * database.
 *
 * <p><b>Version parity.</b> The PostgreSQL and Kafka versions match what the
 * Terraform modules provision on RDS and MSK. Testing against a different major
 * version than production runs is how a migration that works locally fails on
 * deployment.
 */
public final class PlatformContainers {

    /** Matches the engine version in infrastructure/terraform/modules/rds-postgresql. */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17.6-alpine");

    /**
     * Apache Kafka in KRaft mode; matches the MSK major version in the Terraform
     * module. Used with {@code org.testcontainers.kafka.KafkaContainer}, the
     * Apache-native class. The older {@code org.testcontainers.containers.KafkaContainer}
     * is Confluent-specific and rejects this image.
     */
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.0.0");

    /** LocalStack provides the S3 and Secrets Manager APIs used by the document and audit contexts. */
    private static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:4.0.3");

    private static PostgreSQLContainer<?> postgres;
    private static KafkaContainer kafka;
    private static LocalStackContainer localstack;

    private PlatformContainers() {}

    /**
     * The shared PostgreSQL container, started on first use.
     *
     * <p>Synchronised because parallel test classes may race to start it, and two
     * containers would leave half the tests pointing at an empty database.
     */
    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("los")
                    // Synthetic credentials for a throwaway container. Not a
                    // secret, and never used outside a test JVM.
                    .withUsername("los_test")
                    .withPassword("los_test_only");
            postgres.start();
        }
        return postgres;
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer(KAFKA_IMAGE);
            kafka.start();
        }
        return kafka;
    }

    public static synchronized LocalStackContainer localstack() {
        if (localstack == null) {
            localstack = new LocalStackContainer(LOCALSTACK_IMAGE)
                    .withServices(LocalStackContainer.Service.S3, LocalStackContainer.Service.SECRETSMANAGER)
                    // LocalStack accepts any request to a presigned URL by
                    // default, validating neither the signature nor the expiry.
                    // Left at the default, a test asserting that a tampered or
                    // expired URL is refused would pass against real S3 and
                    // silently prove nothing here.
                    .withEnv("S3_SKIP_SIGNATURE_VALIDATION", "0");
            localstack.start();
        }
        return localstack;
    }
}
