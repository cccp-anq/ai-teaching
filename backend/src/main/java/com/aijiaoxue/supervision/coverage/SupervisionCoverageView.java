package com.aijiaoxue.supervision.coverage;

import java.util.List;

/** Summary and both drilldowns produced from one authorized database snapshot. */
public record SupervisionCoverageView(
        SupervisionCoverageSummary summary,
        List<CoverageOfferingRow> covered,
        List<CoverageOfferingRow> uncovered) {
    public SupervisionCoverageView {
        covered = List.copyOf(covered);
        uncovered = List.copyOf(uncovered);
    }
}
