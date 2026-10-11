-- Round 2.1: structure only, no production data or permission grants.
-- Source raw_w/raw_d intentionally allow invalid source values to be retained for diagnostics.
-- Timestamp updates are explicit in future write services; no ORM schema mutation.

CREATE TABLE college (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    college_code VARCHAR(64) NOT NULL UNIQUE,
    college_name VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE semester (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    semester_code VARCHAR(32) NOT NULL UNIQUE,
    semester_name VARCHAR(128) NOT NULL,
    teaching_week_count TINYINT NOT NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    first_week_date DATE NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE course (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_code VARCHAR(64) NULL,
    course_name VARCHAR(255) NOT NULL,
    owner_college_id BIGINT NULL,
    description TEXT NULL,
    syllabus_ref VARCHAR(512) NULL,
    verification_status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_course_college FOREIGN KEY (owner_college_id) REFERENCES college(id)
);
CREATE INDEX idx_course_name ON course (course_name);
CREATE INDEX idx_course_code ON course (course_code);
CREATE INDEX idx_course_owner_college ON course (owner_college_id);

CREATE TABLE course_offering (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NULL,
    semester_id BIGINT NOT NULL,
    source_scope_college_id BIGINT NOT NULL,
    official_offering_code VARCHAR(64) NULL,
    source_offering_key CHAR(64) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    activity_type VARCHAR(32) NOT NULL,
    class_group_text VARCHAR(1000) NOT NULL,
    student_count INT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_offering_course FOREIGN KEY (course_id) REFERENCES course(id),
    CONSTRAINT fk_offering_semester FOREIGN KEY (semester_id) REFERENCES semester(id),
    CONSTRAINT fk_offering_scope FOREIGN KEY (source_scope_college_id) REFERENCES college(id)
);
CREATE UNIQUE INDEX uq_offering_source_key ON course_offering (source_offering_key);
CREATE INDEX idx_offering_semester ON course_offering (semester_id);
CREATE INDEX idx_offering_scope_college ON course_offering (source_scope_college_id);
CREATE INDEX idx_offering_course ON course_offering (course_id);
CREATE INDEX idx_offering_name ON course_offering (display_name);

CREATE TABLE location (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) /*! COLLATE utf8mb4_bin */ NOT NULL,
    location_group VARCHAR(64) /*! COLLATE utf8mb4_bin */ NOT NULL,
    location_type VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uq_location_name_group ON location (normalized_name, location_group);
CREATE INDEX idx_location_group ON location (location_group);

CREATE TABLE teacher (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    employee_no VARCHAR(64) NULL,
    display_name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    source_teacher_key CHAR(64) NULL,
    identity_status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_teacher_name ON teacher (normalized_name);
CREATE INDEX idx_teacher_employee_no ON teacher (employee_no);

CREATE TABLE class_schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    offering_id BIGINT NOT NULL,
    weekday TINYINT NOT NULL,
    start_section SMALLINT NOT NULL,
    end_section SMALLINT NOT NULL,
    location_id BIGINT NOT NULL,
    week_mask CHAR(18) NOT NULL,
    raw_week_text VARCHAR(255) NOT NULL,
    source_schedule_key CHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_schedule_offering FOREIGN KEY (offering_id) REFERENCES course_offering(id),
    CONSTRAINT fk_schedule_location FOREIGN KEY (location_id) REFERENCES location(id),
    CONSTRAINT ck_schedule_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_schedule_sections CHECK (start_section >= 1 AND end_section >= start_section),
    CONSTRAINT ck_schedule_mask CHECK (CHAR_LENGTH(week_mask) = 18 AND CHAR_LENGTH(REPLACE(REPLACE(week_mask, '0', ''), '1', '')) = 0)
);
CREATE UNIQUE INDEX uq_schedule_source_key ON class_schedule (source_schedule_key);
CREATE UNIQUE INDEX uq_schedule_id_offering ON class_schedule (id, offering_id);
CREATE INDEX idx_schedule_offering ON class_schedule (offering_id);
CREATE INDEX idx_schedule_weekday_slot ON class_schedule (weekday, start_section, end_section);
CREATE INDEX idx_schedule_location ON class_schedule (location_id);

CREATE TABLE schedule_week (
    schedule_id BIGINT NOT NULL,
    week_no TINYINT NOT NULL,
    PRIMARY KEY (schedule_id, week_no),
    CONSTRAINT fk_week_schedule FOREIGN KEY (schedule_id) REFERENCES class_schedule(id),
    CONSTRAINT ck_schedule_week CHECK (week_no BETWEEN 1 AND 18)
);
CREATE INDEX idx_schedule_week_lookup ON schedule_week (week_no, schedule_id);

CREATE TABLE schedule_teacher (
    schedule_id BIGINT NOT NULL,
    teacher_id BIGINT NOT NULL,
    PRIMARY KEY (schedule_id, teacher_id),
    CONSTRAINT fk_st_schedule FOREIGN KEY (schedule_id) REFERENCES class_schedule(id),
    CONSTRAINT fk_st_teacher FOREIGN KEY (teacher_id) REFERENCES teacher(id)
);
CREATE INDEX idx_teacher_schedule ON schedule_teacher (teacher_id, schedule_id);

CREATE TABLE user_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    teacher_id BIGINT NULL,
    enabled BOOLEAN NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_teacher FOREIGN KEY (teacher_id) REFERENCES teacher(id)
);


CREATE TABLE role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL UNIQUE,
    role_name VARCHAR(128) NOT NULL
);


CREATE TABLE user_role_scope (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    college_id BIGINT NULL,
    teacher_id BIGINT NULL,
    offering_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_scope_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT fk_scope_role FOREIGN KEY (role_id) REFERENCES role(id),
    CONSTRAINT fk_scope_college FOREIGN KEY (college_id) REFERENCES college(id),
    CONSTRAINT fk_scope_teacher FOREIGN KEY (teacher_id) REFERENCES teacher(id),
    CONSTRAINT fk_scope_offering FOREIGN KEY (offering_id) REFERENCES course_offering(id),
    CONSTRAINT ck_scope_target CHECK (
        (scope_type = 'GLOBAL' AND college_id IS NULL AND teacher_id IS NULL AND offering_id IS NULL) OR
        (scope_type = 'COLLEGE' AND college_id IS NOT NULL AND teacher_id IS NULL AND offering_id IS NULL) OR
        (scope_type = 'SELF_TEACHER' AND teacher_id IS NOT NULL AND college_id IS NULL AND offering_id IS NULL) OR
        (scope_type = 'OFFERING' AND offering_id IS NOT NULL AND college_id IS NULL AND teacher_id IS NULL)
    )
);
CREATE INDEX idx_scope_user_role ON user_role_scope (user_id, role_id);

CREATE TABLE import_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL,
    source_namespace VARCHAR(64) NOT NULL,
    source_title VARCHAR(255) NOT NULL,
    source_scope_college_id BIGINT NOT NULL,
    semester_id BIGINT NOT NULL,
    source_file_name VARCHAR(255) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    parser_version VARCHAR(64) NULL,
    total_rows INT NOT NULL,
    success_rows INT NOT NULL,
    failure_rows INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    operator_user_id BIGINT NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_batch_college FOREIGN KEY (source_scope_college_id) REFERENCES college(id),
    CONSTRAINT fk_batch_semester FOREIGN KEY (semester_id) REFERENCES semester(id),
    CONSTRAINT fk_batch_operator FOREIGN KEY (operator_user_id) REFERENCES user_account(id)
);
CREATE INDEX idx_import_semester_college ON import_batch (semester_id, source_scope_college_id);
CREATE INDEX idx_import_hash ON import_batch (source_sha256);
CREATE INDEX idx_import_status ON import_batch (status);

CREATE TABLE source_timetable_row (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    import_batch_id BIGINT NOT NULL,
    source_index INT NOT NULL,
    raw_n VARCHAR(255) NOT NULL,
    raw_t VARCHAR(32) NOT NULL,
    raw_c VARCHAR(1000) NOT NULL,
    raw_p INT NOT NULL,
    raw_w VARCHAR(255) NOT NULL,
    raw_rw VARCHAR(255) NOT NULL,
    raw_d INT NOT NULL,
    raw_s VARCHAR(32) NOT NULL,
    raw_h VARCHAR(1000) NOT NULL,
    raw_l VARCHAR(255) NOT NULL,
    raw_g VARCHAR(64) NOT NULL,
    raw_json JSON NOT NULL,
    row_hash CHAR(64) NOT NULL,
    normalize_status VARCHAR(32) NOT NULL,
    normalize_error TEXT NULL,
    offering_id BIGINT NULL,
    schedule_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_source_batch FOREIGN KEY (import_batch_id) REFERENCES import_batch(id),
    CONSTRAINT fk_source_offering FOREIGN KEY (offering_id) REFERENCES course_offering(id),
    CONSTRAINT fk_source_schedule FOREIGN KEY (schedule_id) REFERENCES class_schedule(id),
    CONSTRAINT fk_source_schedule_offering FOREIGN KEY (schedule_id, offering_id) REFERENCES class_schedule(id, offering_id)
);
CREATE UNIQUE INDEX uq_source_batch_index ON source_timetable_row (import_batch_id, source_index);
CREATE INDEX idx_source_hash ON source_timetable_row (row_hash);
CREATE INDEX idx_source_status ON source_timetable_row (normalize_status);
CREATE INDEX idx_source_offering ON source_timetable_row (offering_id);
CREATE INDEX idx_source_schedule ON source_timetable_row (schedule_id);
