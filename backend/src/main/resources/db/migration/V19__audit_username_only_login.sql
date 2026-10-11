CREATE TABLE username_only_login_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_account_id BIGINT NOT NULL,
    username VARCHAR(100) NOT NULL,
    remote_address VARCHAR(64) NULL,
    user_agent VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_username_only_login_audit_user
        FOREIGN KEY (user_account_id) REFERENCES user_account(id)
);
CREATE INDEX ix_username_only_login_audit_created
    ON username_only_login_audit (created_at,user_account_id);
