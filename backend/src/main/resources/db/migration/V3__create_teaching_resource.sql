-- V3: Sprint 2 data foundation (DEC-024 B-01/B-02/B-03/B-04).
-- Version assigned by actual creation order (V2 was the previous highest).

-- B-03: teaching resources belong to one CourseOffering; (offering, resource_key) is the
-- DAT-03 upsert identity. Type stays VARCHAR (DOCUMENT/SLIDES/VIDEO/LINK/OTHER minimum set,
-- extensible without schema change); resource_url is an HTTPS locator validated in the
-- application layer. No cascading deletes, consistent with the V2 FK policy.
CREATE TABLE teaching_resource (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_offering_id BIGINT NOT NULL,
    resource_key VARCHAR(128) NOT NULL,
    title VARCHAR(255) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_url VARCHAR(1024) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_teaching_resource_key UNIQUE (course_offering_id, resource_key),
    CONSTRAINT fk_teaching_resource_offering FOREIGN KEY (course_offering_id) REFERENCES course_offering(id)
);
CREATE INDEX idx_teaching_resource_offering ON teaching_resource (course_offering_id);

-- B-04: a preparation-data batch has no single semester/source-scope semantic (rows may
-- address offerings across semesters), so the two batch columns become nullable for this
-- import type. The HTML timetable import keeps providing both values explicitly.
ALTER TABLE import_batch MODIFY source_scope_college_id BIGINT NULL;
ALTER TABLE import_batch MODIFY semester_id BIGINT NULL;
