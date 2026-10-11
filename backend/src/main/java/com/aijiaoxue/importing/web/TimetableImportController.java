package com.aijiaoxue.importing.web;

import com.aijiaoxue.importing.timetable.TimetableImportResult;
import com.aijiaoxue.importing.timetable.TimetableImportService;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class TimetableImportController {
    private final TimetableImportService imports;

    public TimetableImportController(TimetableImportService imports) {
        this.imports = imports;
    }

    @PostMapping(value = "/imports/timetable", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TimetableImportResult importTimetable(@RequestParam("file") MultipartFile file,
                                          @RequestParam long semesterId,
                                          @RequestParam long sourceScopeCollegeId) throws IOException {
        imports.preflightSize(file.getSize());
        return imports.importHtml(file.getOriginalFilename(), file.getBytes(),
                semesterId, sourceScopeCollegeId);
    }
}
