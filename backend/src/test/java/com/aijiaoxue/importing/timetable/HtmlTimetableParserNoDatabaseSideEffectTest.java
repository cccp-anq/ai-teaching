package com.aijiaoxue.importing.timetable;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:timetable-parser-purity;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.clean-disabled=true"
})
@ActiveProfiles("test")
class HtmlTimetableParserNoDatabaseSideEffectTest {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void parsingDoesNotWriteImportOrDomainTables() throws IOException {
        byte[] html;
        try (var input = getClass().getResourceAsStream("/fixtures/timetable-parser-anonymous.html")) {
            assertNotNull(input);
            html = input.readAllBytes();
        }
        int batchesBefore = count("import_batch");
        int rowsBefore = count("source_timetable_row");
        int offeringsBefore = count("course_offering");

        TimetableParseResult result = new HtmlTimetableParser().parse(html);

        assertEquals(9, result.validRowCount());
        assertEquals(batchesBefore, count("import_batch"));
        assertEquals(rowsBefore, count("source_timetable_row"));
        assertEquals(offeringsBefore, count("course_offering"));
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? -1 : count;
    }
}
