package com.aijiaoxue.supervision.coverage;

/**
 * EVA-01 summary under the provisional require1 §4.7 formula (eligible = every offering
 * inside the authorized dataset and current filters; the final eligible definition stays
 * TBD in C-02/OQ-08). coverageRate is null exactly when eligible is 0 — the zero-
 * denominator presentation is OQ-13 and is deliberately not decided here.
 */
public record SupervisionCoverageSummary(
        Long semesterId,
        Long ownerCollegeId,
        long eligible,
        long covered,
        long uncovered,
        Double coverageRate) {
    public static SupervisionCoverageSummary of(Long semesterId, Long ownerCollegeId,
                                                long eligible, long covered) {
        long uncovered = eligible - covered;
        Double rate = eligible == 0 ? null : (double) covered / eligible;
        return new SupervisionCoverageSummary(semesterId, ownerCollegeId, eligible, covered, uncovered, rate);
    }
}
