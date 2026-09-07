# =============================================================================
# One Dockerfile for all four services.
#
# The services differ only in which jar is copied in, so a single parameterised
# build keeps the hardening in one place. Four near-identical Dockerfiles drift:
# a security fix applied to three of them is a security fix that did not happen.
#
# Build:
#   docker build --build-arg SERVICE_NAME=application-service \
#                -f deploy/docker/service.Dockerfile -t los/application-service:0.1.0 .
#
# NOTHING HERE IS EVER PUSHED BY THE BUILD. Publishing is a separate, manual,
# OIDC-authenticated workflow that this repository does not execute.
# =============================================================================

# -----------------------------------------------------------------------------
# Build stage.
#
# Base images are pinned BY DIGEST, not by tag. A tag is mutable: "25-jdk-noble"
# is a different image next month, so a tag-pinned build is not reproducible and
# a vulnerability scan of it expires the moment the tag moves. The digest is the
# only identifier that names one specific set of bytes.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-noble@sha256:534968c051301957beae735e7ba1db54d99ddecf08746d3b9d4f318cc132dbc3 AS build

WORKDIR /build

# Copy the build descriptors first, on their own layer. Dependencies change far
# less often than source, so this layer is reused across almost every build and
# turns a five-minute dependency download into a cache hit.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY shared/event-contracts/pom.xml shared/event-contracts/
COPY shared/test-support/pom.xml shared/test-support/
COPY services/pom.xml services/
COPY services/application-service/pom.xml services/application-service/
COPY services/document-service/pom.xml services/document-service/
COPY services/workflow-service/pom.xml services/workflow-service/
COPY services/audit-service/pom.xml services/audit-service/
COPY tests/architecture/pom.xml tests/architecture/
COPY tests/end-to-end/pom.xml tests/end-to-end/

RUN chmod +x mvnw

# BuildKit cache mount for the local repository. Deliberately a cache mount
# rather than a COPY: baking ~/.m2 into a layer would put every transitive
# dependency into the image history, and any credential in a settings.xml with
# it.
RUN --mount=type=cache,target=/root/.m2/repository \
    ./mvnw -B -ntp -q dependency:go-offline -DskipTests || true

COPY shared shared
COPY services services

ARG SERVICE_NAME
RUN test -n "${SERVICE_NAME}" || (echo "SERVICE_NAME build argument is required" && exit 1)

# Tests are NOT run here. They run in CI, once, against real containers; running
# them again inside an image build would need a Docker daemon inside the build
# and would make the image build fail for reasons that have nothing to do with
# packaging.
RUN --mount=type=cache,target=/root/.m2/repository \
    ./mvnw -B -ntp -pl "services/${SERVICE_NAME}" -am package \
        -DskipTests -DskipITs -Dspotless.check.skip=true

# The Boot plugin publishes the executable jar under the "exec" classifier, so
# the plain jar stays usable as a library by the test modules.
RUN cp "services/${SERVICE_NAME}/target/${SERVICE_NAME}-"*-exec.jar /build/application.jar

# Unpacked into layers so that application code, which changes every build, sits
# above dependencies, which rarely do. A single fat jar is one enormous layer
# that must be pushed and pulled in full for a one-line change.
#
# Boot 4 extracts a runnable layout: a thin application.jar plus a lib/
# directory, wired together by the jar manifest's Class-Path.
RUN java -Djarmode=tools -jar /build/application.jar extract --layers --destination /build/extracted

# -----------------------------------------------------------------------------
# Runtime stage.
#
# A JRE, not a JDK: no compiler, no jlink, no jstack, no Maven. Anything an
# attacker could use to build or inspect is simply absent, and the image is
# roughly a third of the size.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-noble@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e AS runtime

ARG SERVICE_NAME
ARG VERSION="0.0.0-dev"
ARG GIT_SHA="unknown"

# OCI labels, so an image found running somewhere can be traced back to the
# commit that produced it.
LABEL org.opencontainers.image.title="${SERVICE_NAME}" \
      org.opencontainers.image.description="AWS Loan Origination Platform - ${SERVICE_NAME}" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.vendor="AWS Loan Origination Platform contributors" \
      org.opencontainers.image.base.name="eclipse-temurin:25-jre-noble"

# A dedicated unprivileged user with a fixed UID. The fixed UID matters: the
# Kubernetes securityContext sets runAsUser to the same number, and a
# name-based lookup would not work there because the kubelet does not read the
# image's /etc/passwd.
RUN groupadd --system --gid 10001 los \
 && useradd --system --uid 10001 --gid los --home-dir /app --shell /usr/sbin/nologin los \
 && mkdir -p /app /tmp/los \
 && chown -R los:los /app /tmp/los

WORKDIR /app

# The readiness probe. Uses bash's /dev/tcp rather than curl, so the image needs
# no HTTP client -- see the script for why that matters.
COPY --chown=los:los deploy/docker/healthcheck.sh /usr/local/bin/healthcheck
RUN chmod 0555 /usr/local/bin/healthcheck

# Layer order matches change frequency: dependencies first, application last.
COPY --from=build --chown=los:los /build/extracted/dependencies/ ./
COPY --from=build --chown=los:los /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=los:los /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=los:los /build/extracted/application/ ./

USER 10001:10001

# The container writes NOTHING outside /tmp/los, so it runs with a read-only
# root filesystem. Logs go to stdout for the container runtime to collect; there
# is no log file, no PID file and no writable working directory.
ENV JAVA_TOOL_OPTIONS="\
-XX:MaxRAMPercentage=70.0 \
-XX:InitialRAMPercentage=50.0 \
-XX:+UseSerialGC \
-XX:+ExitOnOutOfMemoryError \
-Djava.security.egd=file:/dev/urandom \
-Djava.io.tmpdir=/tmp/los \
-Duser.timezone=UTC \
-Dfile.encoding=UTF-8"

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes itself from the
# container's cgroup limit, so changing the Kubernetes memory limit does not
# require rebuilding the image with a matching heap size -- a mismatch that
# otherwise produces an OOMKill nobody can explain.
#
# ExitOnOutOfMemoryError is deliberate. A JVM that survives an OutOfMemoryError
# is usually in a state where some requests fail and others succeed, which is far
# harder to detect than a pod that dies and is restarted.
#
# SerialGC because these services are small and mostly I/O bound; G1's extra
# threads and memory overhead buy nothing at this heap size.

EXPOSE 8080

# No HEALTHCHECK instruction here on purpose: the port differs per service and
# Kubernetes uses its own probes regardless. The Compose file invokes
# /usr/local/bin/healthcheck with the right port.

# Spring Boot 4's layer extraction produces a RUNNABLE layout: a thin
# application.jar whose manifest Class-Path points at the sibling lib/
# directory. There is no exploded loader to launch, so this is a plain -jar
# invocation and the Boot loader is not involved at runtime at all.
#
# Exec form, so the JVM is PID 1 and receives SIGTERM directly. A shell-form
# entrypoint would put /bin/sh at PID 1, which does not forward the signal, and
# graceful shutdown would silently never happen -- every deployment would cut
# in-flight requests instead of draining them.
ENTRYPOINT ["java", "-jar", "application.jar"]
