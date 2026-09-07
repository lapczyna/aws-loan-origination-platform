package com.example.los.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the hexagonal dependency direction inside every service.
 *
 * <p>Layering is the kind of design that decays quietly. Nobody sets out to
 * import a JPA entity into an aggregate; it happens once, under deadline, and
 * then it is precedent. These rules turn that from a review conversation into a
 * build failure, which is the only form of architectural constraint that
 * survives contact with a delivery schedule.
 *
 * <p>The rules are applied to each service independently rather than to the whole
 * platform at once, so a failure names the service it came from.
 */
class HexagonalArchitectureTest {

    /** The four deployable services, by base package. */
    private static final String APPLICATION = "com.example.los.application";
    private static final String DOCUMENT = "com.example.los.document";
    private static final String WORKFLOW = "com.example.los.workflow";
    private static final String AUDIT = "com.example.los.audit";

    private static JavaClasses serviceClasses(String basePackage) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(basePackage);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {APPLICATION, DOCUMENT, WORKFLOW, AUDIT})
    @DisplayName("dependencies point inwards: adapters depend on use cases, use cases on the domain, never the reverse")
    void layersDependInwardsOnly(String basePackage) {
        Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                // Optional layers, because not every service has every one. The
                // workflow and audit services expose no HTTP API and have no
                // observability package of their own, and an absent layer is a
                // legitimate shape rather than a violation.
                .withOptionalLayers(true)
                .layer("Domain")
                .definedBy(basePackage + ".domain..")
                .layer("UseCase")
                .definedBy(basePackage + ".usecase..")
                .layer("Adapter")
                .definedBy(basePackage + ".adapter..")
                .layer("Config")
                .definedBy(basePackage + ".config..")
                .layer("Observability")
                .definedBy(basePackage + ".observability..")

                // The domain is depended upon by everything and depends on
                // nothing. That is what lets it be reasoned about, and tested,
                // without a database, a broker or a Spring context.
                .whereLayer("Domain")
                .mayOnlyBeAccessedByLayers("UseCase", "Adapter", "Config", "Observability")

                // Use cases orchestrate the domain and are driven by adapters.
                // They must not reach back into an adapter, which would couple
                // the business operation to the technology delivering it.
                .whereLayer("UseCase")
                .mayOnlyBeAccessedByLayers("Adapter", "Config", "Observability")

                // Nothing depends on configuration. It wires the rest together.
                .whereLayer("Config")
                .mayNotBeAccessedByAnyLayer()

                .check(serviceClasses(basePackage));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {APPLICATION, DOCUMENT, WORKFLOW, AUDIT})
    @DisplayName("the domain contains no framework code")
    void domainIsFrameworkFree(String basePackage) {
        // The point of a framework-free domain is not purity for its own sake.
        // It is that the model can be read, tested and changed without a Spring
        // context, and that a framework upgrade cannot force a change to a
        // business rule.
        noClasses()
                .that()
                .resideInAPackage(basePackage + ".domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.servlet..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..",
                        "org.hibernate..",
                        "software.amazon.awssdk..",
                        "org.apache.kafka..",
                        "io.micrometer..")
                .because("the domain must be testable and changeable without any framework on the classpath")
                .check(serviceClasses(basePackage));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {APPLICATION, DOCUMENT, WORKFLOW, AUDIT})
    @DisplayName("use cases depend on ports, never on a specific adapter")
    void useCasesDependOnPortsNotAdapters(String basePackage) {
        // A use case that imports a concrete adapter cannot be tested without
        // that technology, and cannot have it replaced. The one deliberate
        // exception is a persistence-layer exception type an adapter raises and
        // a use case must catch by name.
        noClasses()
                .that()
                .resideInAPackage(basePackage + ".usecase.service..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        basePackage + ".adapter.out.persistence..", basePackage + ".adapter.in..")
                .because("a use case must be drivable and testable without the technology that happens to deliver it")
                .check(serviceClasses(basePackage));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {APPLICATION, DOCUMENT, WORKFLOW, AUDIT})
    @DisplayName("JPA entities never leave the persistence adapter")
    void entitiesDoNotEscapePersistence(String basePackage) {
        // An entity returned from a repository or a controller couples the wire
        // format and the business logic to the database schema, and drags lazy
        // proxies into places that have no session to resolve them.
        noClasses()
                .that()
                .resideOutsideOfPackage(basePackage + ".adapter.out.persistence..")
                .should()
                .dependOnClassesThat()
                .areAnnotatedWith(jakarta.persistence.Entity.class)
                .because("an entity outside its adapter couples every layer to the database schema")
                .check(serviceClasses(basePackage));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {APPLICATION, DOCUMENT, WORKFLOW, AUDIT})
    @DisplayName("inbound web adapters do not reach into persistence directly")
    void controllersDoNotBypassUseCases(String basePackage) {
        // A controller querying the database directly is how business logic ends
        // up in the web layer, where it cannot be reused by a Kafka consumer or a
        // scheduled job and is only testable through HTTP.
        noClasses()
                .that()
                .resideInAPackage(basePackage + ".adapter.in.web..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(basePackage + ".adapter.out.persistence..")
                .because("business logic reached only through HTTP cannot be reused by a consumer or a job")
                // The workflow and audit services have no web adapter at all:
                // they are driven entirely by events and schedulers. A service
                // with nothing to check satisfies this rule trivially rather than
                // failing it.
                .allowEmptyShould(true)
                .check(serviceClasses(basePackage));
    }

    @Test
    @DisplayName("the shared event contracts depend on nothing but Jackson and the JDK")
    void eventContractsStayIndependent() {
        // Every service depends on this module. A dependency added here is a
        // dependency forced on all four, and a version conflict here blocks the
        // whole platform.
        noClasses()
                .that()
                .resideInAPackage("com.example.los.events..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "org.hibernate..",
                        "software.amazon.awssdk..",
                        "org.apache.kafka..",
                        "com.example.los.application..",
                        "com.example.los.document..",
                        "com.example.los.workflow..",
                        "com.example.los.audit..")
                .because("the wire contract must stay independent of any runtime that implements it")
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.example.los.events"));
    }
}
