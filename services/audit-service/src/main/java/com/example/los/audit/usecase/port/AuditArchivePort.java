package com.example.los.audit.usecase.port;

import java.util.List;

import com.example.los.audit.domain.model.AuditRecord;

/**
 * Outbound port for exporting the trail to durable, immutable storage.
 *
 * <p>The database copy is the working one; the export is the copy that survives
 * the database. That distinction matters: an audit trail held only in a database
 * the application can reach is a trail an attacker who reaches that database can
 * rewrite. Exported to an S3 bucket with versioning and Object Lock, the record
 * becomes immutable even to the account that wrote it, for the retention period.
 *
 * <p>The exported head hash is also what makes the chain verifiable after the
 * fact: an auditor comparing today's database against last quarter's export can
 * tell whether history changed underneath them.
 */
public interface AuditArchivePort {

    /**
     * Writes a batch of records to the archive.
     *
     * @return the archive object key the batch was written to
     */
    String archive(String chainKey, List<AuditRecord> records);
}
