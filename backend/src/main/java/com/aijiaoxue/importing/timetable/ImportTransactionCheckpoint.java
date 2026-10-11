package com.aijiaoxue.importing.timetable;

/** No-op production checkpoint that lets integration tests prove rollback after a real Domain write. */
public class ImportTransactionCheckpoint {
    public void afterScheduleWrite() {
        // Production intentionally does nothing.
    }
}
