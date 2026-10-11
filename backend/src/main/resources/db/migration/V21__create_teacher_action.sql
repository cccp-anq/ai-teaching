-- V21: Sprint 2 teacher action ("选择一项下次尝试" / "管理我的当前行动" / "调整行动内容").
-- PRIV3-01: an action belongs to exactly one user. There is deliberately no manager,
-- supervisor or cross-user read path, so the owner column is the only access key.
-- source_kind/source_index keep the link back to the confirmed report suggestion
-- without copying report content into this table.
CREATE TABLE teacher_action (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    media_id BIGINT NULL,
    source_kind VARCHAR(24) NOT NULL,
    source_index INT NULL,
    title VARCHAR(200) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    status VARCHAR(24) NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_teacher_action_owner ON teacher_action (owner_user_id, status);
CREATE INDEX idx_teacher_action_media ON teacher_action (media_id);
