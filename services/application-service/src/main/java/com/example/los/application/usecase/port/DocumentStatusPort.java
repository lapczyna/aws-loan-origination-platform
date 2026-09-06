package com.example.los.application.usecase.port;

import java.util.Set;

import com.example.los.application.domain.model.ApplicationId;
import com.example.los.events.vocabulary.DocumentType;

/**
 * Outbound port telling this context which supporting documents have been
 * scanned and accepted.
 *
 * <p>The application service does not read the document service's tables. It
 * either asks it over HTTP or, in the event-driven path, maintains a local
 * read model fed by document events. Both are implementations of this port.
 */
public interface DocumentStatusPort {

    /** Document categories that have reached the CLEAN state for this application. */
    Set<DocumentType> cleanDocumentTypes(ApplicationId applicationId);
}
