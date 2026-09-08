package com.example.los.application.adapter.in.web.dto;

import java.util.List;

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
        @Schema(
                        description = "The document CATEGORIES that are missing. Never a document's "
                                + "contents, and never a filename.",
                        allowableValues = {"PROOF_OF_IDENTITY", "PROOF_OF_INCOME"})
                List<String> missingDocumentTypes) {}
