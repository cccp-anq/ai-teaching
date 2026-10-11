-- Bounded retry/deadline metadata for restart-safe module orchestration.
ALTER TABLE classroom_module_job ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0;
ALTER TABLE classroom_module_job ADD COLUMN deadline_at DATETIME NULL;

