-- Persist the credential-free OSS object key so a restarted polling job can
-- delete the staged classroom audio when the provider reaches a terminal state.
ALTER TABLE transcription_job ADD COLUMN staging_object_key VARCHAR(1024) NULL;
