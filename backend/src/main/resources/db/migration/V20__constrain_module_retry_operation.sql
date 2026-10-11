ALTER TABLE classroom_module_retry_request
    ADD CONSTRAINT ck_module_retry_operation_type
    CHECK (operation_type IN ('REBUILD','RECOVERY'));
