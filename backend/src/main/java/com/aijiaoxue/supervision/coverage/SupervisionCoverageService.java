package com.aijiaoxue.supervision.coverage;

import com.aijiaoxue.identity.CurrentUser;
import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.AuthorizedScopeProvider;
import com.aijiaoxue.identity.PermissionService;
import com.aijiaoxue.identity.OfferingScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * EVA-01/02/03 read path. Order is fixed: CurrentUser → authorized dataset →
 * semester/ownerCollege filters → eligible → covered → summary; the drilldowns reuse the
 * exact same SQL predicate so counts and lists can never drift. Coverage is computed
 * under the provisional require1 §4.7 formula (see SupervisionCoverageSummary).
 */
@Service
public class SupervisionCoverageService {
    private final CurrentUserProvider currentUsers;
    private final PermissionService permissions;
    private final AuthorizedScopeProvider scopes;
    private final SupervisionCoverageMapper coverage;

    public SupervisionCoverageService(CurrentUserProvider currentUsers, PermissionService permissions,
                                      AuthorizedScopeProvider scopes, SupervisionCoverageMapper coverage) {
        this.currentUsers = currentUsers;
        this.permissions = permissions;
        this.scopes = scopes;
        this.coverage = coverage;
    }

    private OfferingScope authorizedScope() {
        CurrentUser currentUser = currentUsers.requireCurrentUser();
        permissions.requireCourseRead(currentUser);
        return scopes.resolve(currentUser, AuthorizedScopeProvider.Purpose.READ);
    }

    @Transactional(readOnly = true)
    public SupervisionCoverageSummary summary(Long semesterId, Long ownerCollegeId) {
        var scope = authorizedScope();
        long eligible = coverage.countEligible(scope, semesterId, ownerCollegeId);
        long covered = coverage.countCovered(scope, semesterId, ownerCollegeId);
        return SupervisionCoverageSummary.of(semesterId, ownerCollegeId, eligible, covered);
    }

    @Transactional(readOnly = true)
    public List<CoverageOfferingRow> drilldown(Long semesterId, Long ownerCollegeId, boolean covered) {
        var scope = authorizedScope();
        return List.copyOf(coverage.selectDrilldown(scope, semesterId, ownerCollegeId, covered));
    }

    @Transactional(readOnly = true)
    public SupervisionCoverageView view(Long semesterId, Long ownerCollegeId) {
        var rows = coverage.selectSnapshot(authorizedScope(), semesterId, ownerCollegeId);
        var coveredRows = rows.stream().filter(CoverageSnapshotRow::covered)
                .map(CoverageSnapshotRow::offering).toList();
        var uncoveredRows = rows.stream().filter(row -> !row.covered())
                .map(CoverageSnapshotRow::offering).toList();
        return new SupervisionCoverageView(
                SupervisionCoverageSummary.of(semesterId, ownerCollegeId, rows.size(), coveredRows.size()),
                coveredRows, uncoveredRows);
    }
}
