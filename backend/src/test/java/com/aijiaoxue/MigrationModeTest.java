package com.aijiaoxue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "migrate"})
class MigrationModeTest {
    @Autowired Flyway flyway;
    @Autowired ApplicationContext context;

    @Test void migrationOnlyStartsWithoutServletSecurityAndDoesNotReapplyVersions() {
        assertTrue(context.getBeansOfType(SecurityFilterChain.class).isEmpty());
        assertEquals("22", flyway.info().current().getVersion().toString());
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.info().pending().length);
    }
}
