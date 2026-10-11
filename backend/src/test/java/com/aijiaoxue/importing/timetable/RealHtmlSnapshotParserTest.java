package com.aijiaoxue.importing.timetable;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealHtmlSnapshotParserTest {
    @Test
    void parsesLocal538RowSnapshotWithoutPersistingPersonalDataAsATestResource() {
        Path snapshot = findSnapshot();
        assumeTrue(Files.isRegularFile(snapshot),
                "ignored local snapshot .work/course-source-page.html is not available");

        TimetableParseResult result = new HtmlTimetableParser().parse(snapshot);

        assertFalse(result.hasFatalError());
        assertEquals(538, result.totalSourceRows());
        assertEquals(538, result.validRowCount());
        assertEquals(0, result.invalidRowCount());
        assertTrue(result.validationErrors().isEmpty());
        assertTrue(result.parsedRows().stream().allMatch(row -> row.rawW().matches("[01]{18}")));
        assertTrue(result.parsedRows().stream().anyMatch(row -> row.rawP() == 0));
        assertTrue(result.parsedRows().stream().anyMatch(row -> row.rawL().equals("线上")));
        assertTrue(result.parsedRows().stream().anyMatch(row -> row.rawL().equals("待定")));
        assertTrue(result.parsedRows().stream().anyMatch(row -> row.teacherNames().size() > 1));
        assertTrue(result.parsedRows().stream().anyMatch(row ->
                row.weekNumbers().contains(8) && !row.weekNumbers().contains(9)
                        && row.weekNumbers().contains(10)));
    }

    private Path findSnapshot() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path direct = workingDirectory.resolve(".work/course-source-page.html");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path parent = workingDirectory.getParent();
        return parent == null ? direct : parent.resolve(".work/course-source-page.html");
    }
}
