package com.example.los.e2e;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates {@code docs/api/openapi.yaml} from the running services, and fails
 * if the committed document no longer matches.
 *
 * <p><b>Why the document is generated rather than written.</b> A specification
 * maintained by hand drifts, and it drifts silently: nothing fails when a field
 * is renamed or a status code changes, so the document stays plausible and
 * becomes wrong — which is worse than having none, because people trust it.
 * Generating it from the controllers, the DTOs and their bean-validation
 * annotations means the published constraints are the constraints actually
 * enforced.
 *
 * <p><b>Why it is still committed.</b> A document that only exists at runtime
 * cannot be reviewed in a diff, and a breaking change to a public contract is
 * exactly the thing that should be visible in a pull request. So it is generated,
 * committed, and this test is what keeps the two in step.
 *
 * <p><b>Regenerating.</b> When an intentional change makes this fail:
 *
 * <pre>
 *   ./mvnw -pl tests/end-to-end verify -Dlos.openapi.write=true
 * </pre>
 *
 * Then read the diff. If it is larger than the change you made, that is the
 * finding.
 *
 * <p>The document is the <b>union of two services</b>: the application service
 * serves most paths, the document service serves
 * {@code /v1/applications/&#123;applicationId&#125;/documents/**}. They are
 * merged here rather than in a build script because merging needs both services
 * running, and this is the only place where they are.
 */
class OpenApiContractIT extends AbstractEndToEndTest {

    /** Set to regenerate rather than compare. */
    private static final boolean WRITE = Boolean.getBoolean("los.openapi.write");

    private static final Path COMMITTED = Path.of("..", "..", "docs", "api", "openapi.yaml");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Block style, no line wrapping, and no leading {@code ---}.
     *
     * <p>Wrapping matters more than it sounds: the descriptions contain markdown
     * tables, and a wrapped table is not a table any more. It would also make the
     * committed file's diffs depend on line length rather than on content.
     */
    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .enable(YAMLWriteFeature.LITERAL_BLOCK_STYLE)
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .disable(YAMLWriteFeature.SPLIT_LINES)
            .build();

    @Test
    @DisplayName("the committed OpenAPI document matches what the services actually serve")
    void theCommittedDocumentMatchesTheCode() throws IOException {
        ObjectNode application = fetch(platform.applicationServiceUrl());
        ObjectNode document = fetch(platform.documentServiceUrl());

        ObjectNode merged = merge(application, document);
        String generated = YAML.writerWithDefaultPrettyPrinter().writeValueAsString(merged);

        Path target = COMMITTED.toAbsolutePath().normalize();

        if (WRITE) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, generated, StandardCharsets.UTF_8);
            System.out.println("Regenerated " + target);
            return;
        }

        assertThat(target)
                .withFailMessage(
                        "%s does not exist. Generate it with:%n"
                                + "    ./mvnw -pl tests/end-to-end verify -Dlos.openapi.write=true",
                        target)
                .exists();

        String committed = Files.readString(target, StandardCharsets.UTF_8);

        assertThat(normalise(generated))
                .withFailMessage(
                        """
                        The committed OpenAPI document no longer matches what the services serve.

                        This is not a formatting problem. Something about the API changed: a \
                        path, a status code, a schema, a constraint, or a description.

                        If the change was intended, regenerate and READ THE DIFF:
                            ./mvnw -pl tests/end-to-end verify -Dlos.openapi.write=true

                        If the diff is larger than the change you made, that is the finding.

                        Committed at: %s
                        """,
                        target)
                .isEqualTo(normalise(committed));
    }

    @Test
    @DisplayName("the generated document does not leak an applicant's personal data into an example")
    void theDocumentCarriesNoPersonalData() {
        ObjectNode application = fetch(platform.applicationServiceUrl());
        ObjectNode document = fetch(platform.documentServiceUrl());
        String everything = merge(application, document).toString();

        // springdoc will happily put a generated example into a schema, and an
        // example is a value. Nothing that looks like a real person belongs in a
        // published contract.
        for (String marker : com.example.los.testsupport.SensitiveMarkers.all()) {
            assertThat(everything)
                    .withFailMessage("Sensitive marker '%s' reached the OpenAPI document.", marker)
                    .doesNotContain(marker);
        }
        assertThat(everything).doesNotContain("Testine").doesNotContain("ApplicantE2E");
    }

    // -------------------------------------------------------------------------

    /**
     * Fetches a service's generated document.
     *
     * <p>With a token: {@code /v3/api-docs} is not public. The security filter
     * chain permits only the Kubernetes probes without one, deliberately — an
     * unauthenticated API-docs endpoint enumerates the whole attack surface for
     * whoever asks.
     */
    private ObjectNode fetch(String baseUrl) {
        HttpResponse<String> response = get(baseUrl + "/v3/api-docs", platform.applicantToken());

        assertThat(response.statusCode())
                .withFailMessage("Could not read %s/v3/api-docs: %s", baseUrl, response.body())
                .isEqualTo(200);

        return (ObjectNode) JSON.readTree(response.body());
    }

    /**
     * Unions the two documents.
     *
     * <p>The application service is the primary: its {@code info}, {@code servers}
     * and top-level {@code security} become the document's, because the platform's
     * description should have exactly one source. Paths, tags and component
     * schemas are merged.
     *
     * <p><b>A conflict fails loudly</b> rather than letting one service silently
     * win. Two services defining different schemas under the same component name
     * would produce a document that is wrong for one of them, and a merge that
     * picks a side quietly is how that ships.
     */
    private static ObjectNode merge(ObjectNode primary, ObjectNode secondary) {
        ObjectNode merged = primary.deepCopy();

        mergeUnique(merged, secondary, "paths");

        ObjectNode components = (ObjectNode) merged.get("components");
        JsonNode secondaryComponents = secondary.get("components");
        if (secondaryComponents != null) {
            if (components == null) {
                components = merged.putObject("components");
            }
            // Schemas only. The security schemes come from the primary, so a
            // thinner declaration in the secondary cannot overwrite the full one.
            mergeUnique(components, (ObjectNode) secondaryComponents, "schemas");
        }

        mergeTags(merged, secondary);
        sortObjectsInPlace(merged);
        return merged;
    }

    /** Copies every child of {@code section} that the target does not already have. */
    private static void mergeUnique(ObjectNode target, ObjectNode source, String section) {
        JsonNode incoming = source.get(section);
        if (incoming == null) {
            return;
        }
        ObjectNode existing = target.has(section) ? (ObjectNode) target.get(section) : target.putObject(section);

        incoming.properties().forEach(entry -> {
            JsonNode alreadyThere = existing.get(entry.getKey());
            if (alreadyThere == null) {
                existing.set(entry.getKey(), entry.getValue());
            } else if (!alreadyThere.equals(entry.getValue())) {
                throw new IllegalStateException(
                        "Both services define '%s' under '%s', and they disagree. One of the two documents is "
                                .formatted(entry.getKey(), section)
                                + "wrong for its own service, and merging by picking a side would hide that.");
            }
        });
    }

    /** Tags are a list, so they need de-duplicating by name rather than by key. */
    private static void mergeTags(ObjectNode merged, ObjectNode secondary) {
        JsonNode incoming = secondary.get("tags");
        if (incoming == null || !merged.has("tags")) {
            return;
        }
        var existingNames = new java.util.HashSet<String>();
        merged.get("tags").forEach(tag -> existingNames.add(tag.get("name").asString()));

        var tags = (tools.jackson.databind.node.ArrayNode) merged.get("tags");
        incoming.forEach(tag -> {
            if (existingNames.add(tag.get("name").asString())) {
                tags.add(tag);
            }
        });
    }

    /**
     * Sorts every object's keys.
     *
     * <p>springdoc does not guarantee an ordering, and an unordered document
     * produces a diff on every regeneration that has nothing to do with the API.
     * That is how a drift check gets disabled.
     */
    private static void sortObjectsInPlace(ObjectNode node) {
        Map<String, JsonNode> sorted = new TreeMap<>();
        node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));

        node.removeAll();
        sorted.forEach((name, value) -> {
            if (value instanceof ObjectNode child) {
                sortObjectsInPlace(child);
            } else if (value instanceof tools.jackson.databind.node.ArrayNode array) {
                array.forEach(element -> {
                    if (element instanceof ObjectNode child) {
                        sortObjectsInPlace(child);
                    }
                });
            }
            node.set(name, value);
        });
    }

    /** Line-ending and trailing-whitespace differences are not API changes. */
    private static String normalise(String yaml) {
        List<String> lines = yaml.replace("\r\n", "\n")
                .lines()
                .map(String::stripTrailing)
                .toList();
        return String.join("\n", lines).strip();
    }
}
