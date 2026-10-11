package com.aijiaoxue.supervision.evaluation;

import com.aijiaoxue.importing.timetable.TimetableImportError;
import java.util.List;

public class SupervisionImportRejectedException extends RuntimeException {
    private final SupervisionImportResult result;
    private final int httpStatus;

    public SupervisionImportRejectedException(int httpStatus, SupervisionImportResult result) {
        super(result.status().name());
        this.httpStatus = httpStatus;
        this.result = result;
    }

    public SupervisionImportResult result() { return result; }

    public int httpStatus() { return httpStatus; }

    public static SupervisionImportRejectedException rejected(int httpStatus, int totalRows,
                                                             List<TimetableImportError> errors) {
        return new SupervisionImportRejectedException(httpStatus,
                SupervisionImportResult.rejected(totalRows, errors));
    }

    public static SupervisionImportRejectedException fileTooLarge() {
        return new SupervisionImportRejectedException(413, new SupervisionImportResult(null,
                SupervisionImportStatus.REJECTED, 0, 0, 1, 0,
                List.of(new TimetableImportError(null, "text", "Supervision", "TEXT_TOO_LARGE",
                        "评价文本超过 5 MiB 上限"))));
    }
}
