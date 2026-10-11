package com.aijiaoxue.supervision.web;

import com.aijiaoxue.supervision.evaluation.SupervisionImportResult;
import com.aijiaoxue.supervision.evaluation.SupervisionImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** DAT-04 flexible text entry (admin only; CSRF protected). */
@RestController
public class SupervisionImportController {
    private final SupervisionImportService imports;

    public SupervisionImportController(SupervisionImportService imports) {
        this.imports = imports;
    }

    @PostMapping(value = "/imports/supervision-evaluations", consumes = MediaType.APPLICATION_JSON_VALUE)
    SupervisionImportResult importEvaluations(@RequestBody SupervisionTextImportRequest request) {
        return imports.importEvaluations(request.sourceName(), request.text());
    }
}
