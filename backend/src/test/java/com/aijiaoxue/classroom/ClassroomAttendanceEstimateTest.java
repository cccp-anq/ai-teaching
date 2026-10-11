package com.aijiaoxue.classroom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClassroomAttendanceEstimateTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void estimatesFromTopThreeValidAnonymousCountsWithoutInventingAttendanceRate() {
        String raw="""
                {"payload":{"windows":[
                  {"quality":"VALID","visibleStudentCount":18},
                  {"quality":"VALID","visibleStudentCount":21},
                  {"quality":"VALID","visibleStudentCount":20},
                  {"quality":"NO_SUBJECTS","visibleStudentCount":0},
                  {"quality":"LOW_QUALITY","visibleStudentCount":99},
                  {"quality":"VALID","visibleStudentCount":20}
                ]}}
                """;
        var estimate=ClassroomAttendanceEstimate.fromEnvelope(raw,mapper);
        assertEquals(20,estimate.estimatedCount());
        assertEquals(5,estimate.observedWindows());
        assertEquals("HIGH",estimate.confidence());
    }

    @Test void missingOrSparseCountsRemainUnavailable() {
        var estimate=ClassroomAttendanceEstimate.fromEnvelope("""
                {"payload":{"windows":[{"quality":"VALID","visibleStudentCount":12},
                {"quality":"VALID"},{"quality":"REVIEW","visibleStudentCount":15}]}}
                """,mapper);
        assertNull(estimate.estimatedCount());
        assertEquals(1,estimate.observedWindows());
    }
}
