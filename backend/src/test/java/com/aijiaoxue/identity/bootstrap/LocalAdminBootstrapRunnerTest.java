package com.aijiaoxue.identity.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAdminBootstrapRunnerTest {
    private JdbcTemplate jdbc;
    private BCryptPasswordEncoder passwords;

    @BeforeEach
    void setUp() {
        String database = "jdbc:h2:mem:local-admin-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        jdbc = new JdbcTemplate(new DriverManagerDataSource(database, "sa", ""));
        passwords = new BCryptPasswordEncoder(4);
        jdbc.execute("CREATE TABLE role (id BIGINT AUTO_INCREMENT PRIMARY KEY, role_code VARCHAR(64) NOT NULL UNIQUE, role_name VARCHAR(128) NOT NULL)");
        jdbc.execute("CREATE TABLE user_account (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(128) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL, teacher_id BIGINT NULL, enabled BOOLEAN NOT NULL, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
        jdbc.execute("CREATE TABLE user_role_scope (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL, scope_type VARCHAR(32) NOT NULL, college_id BIGINT NULL, teacher_id BIGINT NULL, offering_id BIGINT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }

    @Test
    void createsThenRotatesOneEnabledGlobalAdminWithoutStoringPlaintext() throws Exception {
        run("local-admin", "First-Strong-Password!2026");
        String firstHash = jdbc.queryForObject("SELECT password_hash FROM user_account WHERE username='local-admin'", String.class);
        assertTrue(passwords.matches("First-Strong-Password!2026", firstHash));
        assertEquals(1L, count("SELECT COUNT(*) FROM role WHERE role_code='ADMIN'"));
        assertEquals(1L, count("SELECT COUNT(*) FROM user_account WHERE username='local-admin' AND enabled=TRUE AND teacher_id IS NULL"));
        assertEquals(1L, count("SELECT COUNT(*) FROM user_role_scope s JOIN role r ON r.id=s.role_id WHERE r.role_code='ADMIN' AND s.scope_type='GLOBAL'"));

        run("local-admin", "Second-Strong-Password!2026");
        String secondHash = jdbc.queryForObject("SELECT password_hash FROM user_account WHERE username='local-admin'", String.class);
        assertTrue(passwords.matches("Second-Strong-Password!2026", secondHash));
        assertEquals(1L, count("SELECT COUNT(*) FROM user_account"));
        assertEquals(1L, count("SELECT COUNT(*) FROM user_role_scope"));
    }

    @Test
    void rejectsMissingWeakOrUnsafeCredentialsBeforeWriting() {
        assertThrows(IllegalArgumentException.class,
                () -> new LocalAdminBootstrapRunner(jdbc, new MockEnvironment(), passwords).run(null));
        assertThrows(IllegalArgumentException.class, () -> run("admin with spaces", "Strong-Password!2026"));
        assertThrows(IllegalArgumentException.class, () -> run("local-admin", "short"));
        assertEquals(0L, count("SELECT COUNT(*) FROM user_account"));
    }

    private void run(String username, String password) throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("LOCAL_ADMIN_USERNAME", username)
                .withProperty("LOCAL_ADMIN_PASSWORD", password);
        new LocalAdminBootstrapRunner(jdbc, environment, passwords).run(null);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
