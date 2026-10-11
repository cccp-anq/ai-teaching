package com.aijiaoxue.identity.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Explicit local-only account bootstrap. It is never active during a normal application start.
 * Credentials are supplied by the invoking process and only the BCrypt digest is persisted.
 */
@Component
@Profile("local-admin-bootstrap")
public class LocalAdminBootstrapRunner implements ApplicationRunner {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");

    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final PasswordEncoder passwords;

    public LocalAdminBootstrapRunner(JdbcTemplate jdbc, Environment environment, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = required("LOCAL_ADMIN_USERNAME").trim();
        String password = required("LOCAL_ADMIN_PASSWORD");
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("LOCAL_ADMIN_USERNAME must match [A-Za-z0-9._-]{3,64}");
        }
        if (password.length() < 12) {
            throw new IllegalArgumentException("LOCAL_ADMIN_PASSWORD must contain at least 12 characters");
        }

        Timestamp now = Timestamp.from(Instant.now());
        Long roleId = firstLong("SELECT id FROM role WHERE role_code='ADMIN'");
        if (roleId == null) {
            jdbc.update("INSERT INTO role (role_code, role_name) VALUES ('ADMIN', ?)", "管理员");
            roleId = requiredLong("SELECT id FROM role WHERE role_code='ADMIN'");
        }

        Long userId = firstLong("SELECT id FROM user_account WHERE username=?", username);
        String digest = passwords.encode(password);
        if (userId == null) {
            jdbc.update("INSERT INTO user_account (username, password_hash, teacher_id, enabled, created_at, updated_at) VALUES (?,?,NULL,TRUE,?,?)",
                    username, digest, now, now);
            userId = requiredLong("SELECT id FROM user_account WHERE username=?", username);
        } else {
            jdbc.update("UPDATE user_account SET password_hash=?, teacher_id=NULL, enabled=TRUE, updated_at=? WHERE id=?",
                    digest, now, userId);
        }

        // This command defines a dedicated administrator identity, so stale or narrower grants are removed.
        jdbc.update("DELETE FROM user_role_scope WHERE user_id=?", userId);
        jdbc.update("INSERT INTO user_role_scope (user_id, role_id, scope_type) VALUES (?,?,'GLOBAL')",
                userId, roleId);
        System.out.printf("[local-admin-bootstrap] account '%s' is enabled with ADMIN/GLOBAL access.%n", username);
    }

    private String required(String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private Long firstLong(String sql, Object... parameters) {
        List<Long> values = jdbc.query(sql, (row, index) -> row.getLong(1), parameters);
        return values.isEmpty() ? null : values.get(0);
    }

    private Long requiredLong(String sql, Object... parameters) {
        Long value = firstLong(sql, parameters);
        if (value == null) throw new IllegalStateException("bootstrap row was not persisted");
        return value;
    }
}
