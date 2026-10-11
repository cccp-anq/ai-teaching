-- Module envelopes for full-length classes can exceed MySQL TEXT's 64 KiB limit.
-- The HTTP response remains bounded separately by app.classroom-analysis.response-max-bytes.
ALTER TABLE classroom_module_result
    MODIFY COLUMN envelope_json MEDIUMTEXT NOT NULL;
