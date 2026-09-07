package com.example.los.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import com.example.los.testsupport.PlatformContainers;
import com.example.los.testsupport.TestJwtIssuer;

/**
 * All four services, running as four separate operating-system processes,
 * against shared PostgreSQL, Kafka and LocalStack containers.
 *
 * <p><b>Why separate processes rather than four Spring contexts in one JVM.</b>
 * One JVM was the obvious design and it does not work, for two concrete reasons
 * that are worth writing down because they are easy to rediscover:
 *
 * <ul>
 *   <li>Every service jar contains {@code /application.yaml}. On a shared
 *       classpath, Spring resolves that to whichever jar the class loader
 *       reaches first, so three of the four services would silently boot with
 *       another service's configuration.
 *   <li>Every service jar contains {@code db/migration/V1__*.sql}. Flyway would
 *       scan the whole classpath, find four different migrations all claiming
 *       version 1, and refuse to start.
 * </ul>
 *
 * <p>Both could be worked around -- per-service config locations, per-service
 * migration paths, or a class loader per service. Each workaround makes the
 * tested configuration differ from the deployed one, which is exactly the
 * property an end-to-end test exists to check. Launching the real executable
 * jars avoids all of it: each process sees only its own configuration and its
 * own migrations, because that is all that is on its classpath.
 *
 * <p><b>What is real here.</b> Real HTTP between the test and the services, real
 * Kafka delivery between services, real outbox draining, real presigned uploads
 * to a real S3 API, real Flyway migrations, real token validation against a real
 * OIDC issuer. Nothing is passed between services as a Java object, which is the
 * shortcut that lets an end-to-end test pass while the deployed system fails on
 * a serialisation change only one side knows about.
 *
 * <p><b>What is not real.</b> The four external checks and the malware scanner
 * are simulations, as they are everywhere in this repository. The services run
 * from jars on the host rather than from container images; the images and the
 * Compose stack are exercised separately by
 * {@code scripts/local-smoke-test.sh}, so neither path is unverified.
 *
 * <p>Started once per JVM and torn down on exit.
 */
final class PlatformUnderTest {

    /** The audience every service is configured to accept. */
    static final String AUDIENCE = "los-e2e-api";

    private static final String DOCUMENTS_BUCKET = "los-e2e-documents";

    /**
     * How often the outbox publisher, the workflow poller and the document
     * scanner run.
     *
     * <p>Short, because these tests wait for the chain to advance and every wait
     * is bounded by it. Not shorter: a very small interval spends the suite's
     * time on empty polls and buries a real failure in log noise.
     */
    private static final String POLL_INTERVAL = "PT0.2S";

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    private static PlatformUnderTest instance;

    private final TestJwtIssuer jwtIssuer;
    private final Path pepperFile;
    private final Path logDirectory;
    private final List<ServiceProcess> services = new ArrayList<>();

    private ServiceProcess applicationService;
    private ServiceProcess documentService;

    private PlatformUnderTest(TestJwtIssuer jwtIssuer, Path pepperFile, Path logDirectory) {
        this.jwtIssuer = jwtIssuer;
        this.pepperFile = pepperFile;
        this.logDirectory = logDirectory;
    }

    /**
     * Starts the platform on first use.
     *
     * <p>Synchronised because JUnit may instantiate several test classes
     * concurrently, and two platforms would bind two sets of ports and leave
     * half the tests talking to the wrong one.
     */
    static synchronized PlatformUnderTest start() {
        if (instance == null) {
            PlatformUnderTest platform = new PlatformUnderTest(
                    TestJwtIssuer.start(), writeSyntheticPepper(), createLogDirectory());
            // A shutdown hook rather than an @AfterAll: the platform outlives any
            // single test class, and a killed build must not leave four orphaned
            // JVMs holding ports.
            Runtime.getRuntime().addShutdownHook(new Thread(platform::stop, "platform-under-test-shutdown"));
            try {
                platform.bootstrapObjectStorage();
                platform.startServices();
            } catch (RuntimeException | Error startupFailure) {
                platform.stop();
                throw startupFailure;
            }
            instance = platform;
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // Startup
    // -------------------------------------------------------------------------

    /**
     * Creates the documents bucket in LocalStack.
     *
     * <p>Done here rather than by the document service, deliberately: a service
     * holding {@code s3:CreateBucket} is a service that can create a bucket
     * without the encryption, versioning and public-access-block settings the
     * Terraform module applies. In a deployment the bucket is Terraform's.
     */
    private void bootstrapObjectStorage() {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(PlatformContainers.localstack().getEndpoint())
                .region(Region.of(PlatformContainers.localstack().getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        // LocalStack accepts any credential. Not secrets, and they
                        // reach nothing outside the container.
                        PlatformContainers.localstack().getAccessKey(),
                        PlatformContainers.localstack().getSecretKey())))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {

            s3.createBucket(CreateBucketRequest.builder().bucket(DOCUMENTS_BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException alreadyExists) {
            // A previous run against the same container. Not a problem.
        }
    }

    private void startServices() {
        applicationService = launch(
                "application-service",
                "--los.security.applicant-pepper-file=" + pepperFile,
                "--los.outbox.poll-interval=" + POLL_INTERVAL,
                "--los.idempotency.purge-interval=PT1H");

        documentService = launch(
                "document-service",
                "--los.aws.region=" + PlatformContainers.localstack().getRegion(),
                "--los.aws.endpoint-override="
                        + PlatformContainers.localstack().getEndpoint(),
                // The presigner and the client reach LocalStack by the same route
                // here, because everything runs on the host. In Compose they do
                // not, which is why the setting exists at all.
                "--los.aws.presign-endpoint-override="
                        + PlatformContainers.localstack().getEndpoint(),
                "--los.documents.bucket=" + DOCUMENTS_BUCKET,
                "--los.outbox.poll-interval=" + POLL_INTERVAL,
                "--los.scanner.poll-interval=" + POLL_INTERVAL,
                "--los.scanner.simulated-latency=PT0S",
                "--los.scanner.abandoned-sweep-interval=PT1H");

        launch(
                "workflow-service",
                "--los.outbox.poll-interval=" + POLL_INTERVAL,
                "--los.workflow.poll-interval=" + POLL_INTERVAL,
                "--los.workflow.lease=PT5S",
                "--los.workflow.base-backoff=PT0.05S",
                "--los.workflow.max-backoff=PT0.5S",
                "--los.workflow.max-attempts=3",
                "--los.checks.timeout=PT2S");

        launch("audit-service");

        // Started in parallel above, waited for here. Four sequential startups
        // would add roughly a minute to every run, and the services genuinely do
        // not depend on each other's startup order -- a platform that did could
        // not survive a rolling restart.
        for (ServiceProcess service : services) {
            service.awaitReady(STARTUP_TIMEOUT);
        }
    }

    private ServiceProcess launch(String serviceName, String... extraArguments) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--server.port=" + reserveFreePort());
        arguments.add("--spring.datasource.url=" + PlatformContainers.postgres().getJdbcUrl());
        arguments.add("--spring.datasource.username="
                + PlatformContainers.postgres().getUsername());
        arguments.add("--spring.datasource.password="
                + PlatformContainers.postgres().getPassword());
        arguments.add("--spring.kafka.bootstrap-servers="
                + PlatformContainers.kafka().getBootstrapServers());
        arguments.add("--spring.kafka.security.protocol=PLAINTEXT");
        // earliest, so a consumer that joins after a producer has already
        // published still sees the message. Without it the first test in a run
        // races the consumer group's initial offset assignment.
        arguments.add("--spring.kafka.consumer.auto-offset-reset=earliest");
        arguments.add("--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + jwtIssuer.issuerUri());
        arguments.add("--los.security.accepted-audiences=" + AUDIENCE);
        // Health details are needed to see WHY a service is not ready. The
        // endpoint is on a throwaway process reachable only from this machine.
        arguments.add("--management.endpoint.health.show-details=always");
        arguments.add("--management.endpoint.health.show-components=always");
        arguments.addAll(Arrays.asList(extraArguments));

        ServiceProcess service = ServiceProcess.launch(serviceName, logDirectory, arguments);
        services.add(service);
        return service;
    }

    /**
     * Picks a port the operating system says is free.
     *
     * <p>There is an unavoidable race: the port is free when this returns and
     * could in principle be taken before the service binds it. Asking the
     * service to bind port 0 and report back would remove the race but requires
     * parsing its startup log for the chosen port, which is a worse thing to
     * depend on. The window is microseconds on a machine that is not
     * simultaneously opening hundreds of sockets.
     */
    private static int reserveFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not reserve a free port for a service", e);
        }
    }

    // -------------------------------------------------------------------------
    // What the tests need
    // -------------------------------------------------------------------------

    String applicationServiceUrl() {
        return applicationService.baseUrl();
    }

    String documentServiceUrl() {
        return documentService.baseUrl();
    }

    TestJwtIssuer jwtIssuer() {
        return jwtIssuer;
    }

    /** A token with the scopes an applicant-facing client holds. */
    String applicantToken() {
        return jwtIssuer.mintToken(
                "e2e-applicant-client",
                AUDIENCE,
                "applications:read",
                "applications:write",
                "documents:read",
                "documents:write");
    }

    /** A token with the scopes an internal reviewer holds. */
    String reviewerToken(String reviewerSubject) {
        return jwtIssuer.mintToken(
                reviewerSubject, AUDIENCE, "applications:read", "manual-review:read", "manual-review:decide");
    }

    /**
     * How long a test should wait for the chain to advance.
     *
     * <p>Generous relative to the poll interval. A tight bound turns a slow CI
     * runner into a flaky suite, and a flaky suite is one people rerun until it
     * passes, which is the same as not having it.
     */
    static Duration patience() {
        return Duration.ofSeconds(45);
    }

    /** Where each service's output was written, for a failure that needs explaining. */
    Path logDirectory() {
        return logDirectory;
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    private void stop() {
        for (int i = services.size() - 1; i >= 0; i--) {
            services.get(i).stop();
        }
    }

    private static Path createLogDirectory() {
        try {
            Path directory = Files.createTempDirectory("los-e2e-logs");
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create a directory for service logs", e);
        }
    }

    /**
     * Writes a synthetic pepper to a temporary file.
     *
     * <p>A file, not a property, because that is how every deployed service
     * reads it: an environment variable appears in {@code /proc}, in crash dumps
     * and in {@code docker inspect}. Testing the file path tests the code path
     * that actually runs.
     *
     * <p><b>DO NOT CHANGE THIS STRING CASUALLY.</b> The applicant reference is an
     * HMAC under this pepper, the simulated credit score is derived from that
     * reference, and the tests in {@code AbstractEndToEndTest} name applicants
     * chosen so their scores land in specific decision bands. Changing the pepper
     * moves every one of them to a different band, and the failures will look
     * like a broken decision policy rather than a changed constant.
     */
    private static Path writeSyntheticPepper() {
        try {
            Path file = Files.createTempFile("los-e2e-pepper", ".txt");
            Files.writeString(file, "synthetic-e2e-pepper-not-a-secret-0123456789", StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the synthetic end-to-end pepper", e);
        }
    }

    // =========================================================================
    // One service process.
    // =========================================================================

    private static final class ServiceProcess {

        private static final HttpClient HTTP = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        private final String name;
        private final int port;
        private final Process process;
        private final Path logFile;

        private ServiceProcess(String name, int port, Process process, Path logFile) {
            this.name = name;
            this.port = port;
            this.process = process;
            this.logFile = logFile;
        }

        static ServiceProcess launch(String name, Path logDirectory, List<String> arguments) {
            Path jar = executableJar(name);
            int port = portFrom(arguments);

            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            // A small heap: four JVMs on a CI runner with a default heap each can
            // exhaust the machine, and the failure looks like an unrelated timeout.
            command.add("-Xmx320m");
            command.add("-XX:TieredStopAtLevel=1");
            command.add("-jar");
            command.add(jar.toString());
            command.addAll(arguments);

            Path logFile = logDirectory.resolve(name + ".log");

            try {
                Process process = new ProcessBuilder(command)
                        // Merged and redirected to a file. Draining two pipes per
                        // service from the test JVM would be four more threads and
                        // a deadlock waiting to happen if one filled up.
                        .redirectErrorStream(true)
                        .redirectOutput(logFile.toFile())
                        .start();
                return new ServiceProcess(name, port, process, logFile);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start " + name, e);
            }
        }

        /**
         * Waits until the service reports readiness.
         *
         * <p>Readiness, not liveness: liveness deliberately excludes the
         * database, so a service that cannot reach PostgreSQL would look alive
         * and then fail every test with something unhelpful.
         */
        void awaitReady(Duration timeout) {
            Instant deadline = Instant.now().plus(timeout);
            String lastFailure = "no response yet";

            while (Instant.now().isBefore(deadline)) {
                if (!process.isAlive()) {
                    throw new IllegalStateException(
                            name + " exited during startup with code " + process.exitValue() + ".\n" + tail(logFile));
                }
                try {
                    HttpResponse<String> response = HTTP.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(baseUrl() + "/actuator/health/readiness"))
                                    .timeout(Duration.ofSeconds(2))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        return;
                    }
                    lastFailure = "HTTP " + response.statusCode() + ": " + response.body();
                } catch (IOException stillStarting) {
                    lastFailure = stillStarting.getMessage();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for " + name, e);
                }

                sleep(Duration.ofMillis(250));
            }

            throw new IllegalStateException(
                    name + " was not ready within " + timeout + ". Last check: " + lastFailure + "\n" + tail(logFile));
        }

        String baseUrl() {
            return "http://localhost:" + port;
        }

        void stop() {
            if (!process.isAlive()) {
                return;
            }
            // A graceful request first: the services are configured for graceful
            // shutdown, and killing them outright would leave Kafka consumer
            // group rebalances outstanding that slow the next run down.
            process.destroy();
            try {
                if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        private static int portFrom(List<String> arguments) {
            return arguments.stream()
                    .filter(argument -> argument.startsWith("--server.port="))
                    .map(argument -> Integer.parseInt(argument.substring("--server.port=".length())))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No --server.port argument was supplied"));
        }

        /**
         * Locates a service's executable jar.
         *
         * <p>The directory comes from a system property set by Failsafe in this
         * module's POM, so the tests do not guess at the repository layout from
         * the working directory.
         */
        private static Path executableJar(String serviceName) {
            String servicesDirectory = System.getProperty("los.services.directory");
            String version = System.getProperty("los.project.version");

            if (servicesDirectory == null || version == null) {
                throw new IllegalStateException(
                        "los.services.directory and los.project.version must be set. "
                                + "They are configured on the Failsafe plugin in tests/end-to-end/pom.xml, "
                                + "so this suite is run with 'mvn verify' rather than directly from an IDE "
                                + "without those properties.");
            }

            Path jar = Path.of(servicesDirectory, serviceName, "target", serviceName + "-" + version + "-exec.jar");

            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("No executable jar at " + jar + ". Run 'mvn package' first: the "
                        + "end-to-end suite runs the real jars, not the classes.");
            }
            return jar;
        }

        private static String javaExecutable() {
            // The JVM running the tests, so the services run on the same Java
            // version the build was verified with rather than whatever `java`
            // happens to be first on PATH.
            return Path.of(System.getProperty("java.home"), "bin", "java").toString();
        }

        /** The end of a service's log, for a failure message worth reading. */
        private static String tail(Path logFile) {
            try {
                List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
                List<String> lastLines = lines.subList(Math.max(0, lines.size() - 40), lines.size());
                return "---- last 40 lines of " + logFile + " ----\n" + String.join("\n", lastLines);
            } catch (IOException e) {
                return "(could not read " + logFile + ": " + e.getMessage() + ")";
            }
        }

        private static void sleep(Duration duration) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for a service to start", e);
            }
        }
    }
}
