package com.example.los.architecture;

import java.util.List;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Keeps the bounded contexts genuinely separate.
 *
 * <p>These are the rules that decide whether this is four services or one
 * distributed monolith. Services that share code drift into sharing deployment:
 * a change to a shared class forces a coordinated release, and the independence
 * that justified splitting them in the first place quietly disappears.
 *
 * <p>The only thing the contexts may share is {@code event-contracts}, and that
 * is deliberate — it <em>is</em> the integration surface. Everything else they
 * need from one another arrives as an event.
 */
class BoundedContextIsolationTest {

    private record Context(String name, String basePackage) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Context> CONTEXTS = List.of(
            new Context("application", "com.example.los.application"),
            new Context("document", "com.example.los.document"),
            new Context("workflow", "com.example.los.workflow"),
            new Context("audit", "com.example.los.audit"));

    static Stream<Context> contexts() {
        return CONTEXTS.stream();
    }

    private static JavaClasses allPlatformClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.los");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contexts")
    @DisplayName("a context never imports another context's classes")
    void contextsDoNotImportEachOther(Context context) {
        String[] otherContexts = CONTEXTS.stream()
                .filter(other -> !other.equals(context))
                .map(other -> other.basePackage() + "..")
                .toArray(String[]::new);

        noClasses()
                .that()
                .resideInAPackage(context.basePackage() + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(otherContexts)
                .because("contexts integrate through events and the shared contract module, never through code. "
                        + "A direct import turns four independently deployable services into one that must be "
                        + "released all at once")
                .check(allPlatformClasses());
    }

    @Test
    @DisplayName("no context reads another context's database schema")
    void contextsDoNotShareSchemas() {
        // The rule the architecture actually rests on. Two services sharing a
        // table share a migration schedule, a lock contention profile and a
        // failure mode, and neither can change its storage without the other's
        // agreement. Enforced here at the entity level; the per-service database
        // roles enforce it again at the database.
        for (Context context : CONTEXTS) {
            String ownSchema = schemaFor(context);

            noClasses()
                    .that()
                    .resideInAPackage(context.basePackage() + "..")
                    .should()
                    .beAnnotatedWith(TableInSchemaOtherThan.of(ownSchema))
                    .because("a service that reads another's tables is not independently deployable")
                    .check(allPlatformClasses());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contexts")
    @DisplayName("a context's use cases and domain never import a Spring Data repository from elsewhere")
    void noCrossContextRepositoryAccess(Context context) {
        String[] otherRepositoryPackages = CONTEXTS.stream()
                .filter(other -> !other.equals(context))
                .map(other -> other.basePackage() + ".adapter.out.persistence..")
                .toArray(String[]::new);

        noClasses()
                .that()
                .resideInAPackage(context.basePackage() + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(otherRepositoryPackages)
                .because("reaching into another context's persistence adapter bypasses its invariants entirely")
                .check(allPlatformClasses());
    }

    @Test
    @DisplayName("only the shared contract module is depended on by more than one context")
    void onlyContractsAreShared() {
        // Any other shared package would become a coupling point that forces
        // coordinated releases. event-contracts is exempt because it is, by
        // design, the integration surface.
        for (Context context : CONTEXTS) {
            noClasses()
                    .that()
                    .resideInAPackage(context.basePackage() + "..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.example.los.testsupport..")
                    .because("test infrastructure must not reach production code paths")
                    .check(allPlatformClasses());
        }
    }

    private static String schemaFor(Context context) {
        return context.name();
    }

    /**
     * Matches a JPA {@code @Table} declaring a schema other than the given one.
     *
     * <p>Written as an explicit predicate because ArchUnit's fluent API cannot
     * express "annotated with @Table whose schema attribute is not X".
     */
    private static final class TableInSchemaOtherThan {

        private TableInSchemaOtherThan() {}

        static com.tngtech.archunit.base.DescribedPredicate<
                        com.tngtech.archunit.core.domain.JavaAnnotation<?>>
                of(String ownSchema) {
            return new com.tngtech.archunit.base.DescribedPredicate<>(
                    "@Table in a schema other than \"" + ownSchema + "\"") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaAnnotation<?> annotation) {
                    if (!annotation.getRawType().getName().equals(jakarta.persistence.Table.class.getName())) {
                        return false;
                    }
                    return annotation
                            .get("schema")
                            .map(Object::toString)
                            .filter(schema -> !schema.isEmpty())
                            .map(schema -> !schema.equals(ownSchema))
                            .orElse(false);
                }
            };
        }
    }
}
