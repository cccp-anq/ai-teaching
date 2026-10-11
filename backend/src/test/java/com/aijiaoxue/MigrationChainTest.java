package com.aijiaoxue;

import java.sql.DriverManager;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Runs production migrations against a fresh database; does not claim business constraint coverage. */
class MigrationChainTest {
    @Test
    void freshDatabaseMigratesAndRepeatedRunPreservesHistory() throws Exception {
        boolean mysql = Boolean.getBoolean("mysqlMigration");
        String url = mysql ? requiredEnvironment("MIGRATION_TEST_URL")
                : "jdbc:h2:mem:migration_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql) {
            assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}"
                    + "\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"),
                    "MySQL test must target a dedicated loopback migration database");
        }
        String username = mysql ? requiredEnvironment("MIGRATION_TEST_USERNAME") : "sa";
        String password = mysql ? requiredEnvironment("MIGRATION_TEST_PASSWORD") : "";
        try (var connection = DriverManager.getConnection(url, username, password);
             var tables = connection.getMetaData().getTables(connection.getCatalog(),
                     mysql ? null : "PUBLIC", "%", new String[]{"TABLE"})) {
            assertFalse(tables.next(), "Fresh-database test refuses an existing schema; it never drops tables");
        }
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false).load();
        int pending = flyway.info().pending().length;
        assertTrue(pending > 0, "Production migrations must be discovered");
        assertEquals(pending, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(0, flyway.info().pending().length);
        String[] history = snapshot(flyway.info().applied());
        assertEquals(pending, history.length);

        // Recreate Flyway to represent a new process, not a cached result on the first instance.
        Flyway repeat = Flyway.configure().configuration(flyway.getConfiguration()).load();
        assertEquals(0, repeat.migrate().migrationsExecuted);
        repeat.validate();
        assertArrayEquals(history, snapshot(repeat.info().applied()));
        assertThrows(FlywayException.class, repeat::clean, "Clean must remain disabled");
        assertArrayEquals(history, snapshot(repeat.info().applied()));
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        assertNotNull(value, "Missing " + key + "; run scripts/Test-MigrationDatabase.ps1");
        assertFalse(value.isBlank(), "Empty " + key);
        return value;
    }

    private static String[] snapshot(MigrationInfo[] migrations) {
        return Arrays.stream(migrations).map(m -> m.getVersion() + ":" + m.getScript() + ":"
                + m.getChecksum() + ":" + m.getInstalledOn() + ":" + m.getState()).toArray(String[]::new);
    }
}
