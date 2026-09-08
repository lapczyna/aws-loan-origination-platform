package com.example.los.application.adapter.in.web.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The 422 returned when a submission is refused for want of a document.
 *
 * <p>A documentation type, like {@link ProblemResponse}, and it exists because
 * the extension property below is the only part of the response a client can
 * <em>act</em> on: it says which categories to collect. Publishing the generic
 * problem shape here would leave the caller to discover that field by
 * experiment.
 */
@Schema(
        name = "MissingDocumentsProblem",
        description = "Returned when an application cannot be submitted because a mandatory document "
                + "has not been uploaded and accepted.")
public record MissingDocumentsProblem(
        @Schema(example = "MANDATORY_DOCUMENTS_MISSING") String errorCode,
        @ArraySchema(
                        // @ArraySchema, not @Schema. On a List, @Schema(allowableValues=...)
                        // puts the enum on the ARRAY rather than on its items, producing
                        // `type: array` alongside `enum: [PROOF_OF_IDENTITY, ...]` -- which is
                        // invalid OpenAPI, and which Redocly rejects. The mistake generates
                        // silently; only linting the output catches it.
                        arraySchema =
                                @Schema(
                                        description = "The document CATEGORIES that are missing. Never a "
                                                + "document's contents, and never a filename."),
                        schema = @Schema(allowableValues = {"PROOF_OF_IDENTITY", "PROOF_OF_INCOME"}))
                List<String> missingDocumentTypes) {}
