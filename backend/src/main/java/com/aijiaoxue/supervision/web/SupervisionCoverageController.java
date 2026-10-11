package com.aijiaoxue.supervision.web;

import com.aijiaoxue.supervision.coverage.CoverageOfferingRow;
import com.aijiaoxue.supervision.coverage.SupervisionCoverageService;
import com.aijiaoxue.supervision.coverage.SupervisionCoverageSummary;
import com.aijiaoxue.supervision.coverage.SupervisionCoverageView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** EVA-01/02/03 read endpoints; server-side authorization always applies. */
@RestController
public class SupervisionCoverageController {
    private final SupervisionCoverageService coverage;

    public SupervisionCoverageController(SupervisionCoverageService coverage) {
        this.coverage = coverage;
    }

    @GetMapping("/supervision/coverage")
    SupervisionCoverageSummary summary(@RequestParam(required = false) Long semesterId,
                                       @RequestParam(required = false) Long ownerCollegeId) {
        return coverage.summary(semesterId, ownerCollegeId);
    }

    @GetMapping("/supervision/coverage/view")
    SupervisionCoverageView view(@RequestParam(required = false) Long semesterId,
                                 @RequestParam(required = false) Long ownerCollegeId) {
        return coverage.view(semesterId, ownerCollegeId);
    }

    @GetMapping("/supervision/coverage/covered")
    List<CoverageOfferingRow> covered(@RequestParam(required = false) Long semesterId,
                                      @RequestParam(required = false) Long ownerCollegeId) {
        return coverage.drilldown(semesterId, ownerCollegeId, true);
    }

    @GetMapping("/supervision/coverage/uncovered")
    List<CoverageOfferingRow> uncovered(@RequestParam(required = false) Long semesterId,
                                        @RequestParam(required = false) Long ownerCollegeId) {
        return coverage.drilldown(semesterId, ownerCollegeId, false);
    }
}
