package com.aijiaoxue.identity;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
    public enum Capability { TIMETABLE_IMPORT, COURSE_READ, CLASSROOM_READ, CLASSROOM_WRITE }
    private final CurrentUserProvider currentUsers;
    private final AuthorizedScopeProvider scopes;

    public PermissionService(CurrentUserProvider currentUsers, AuthorizedScopeProvider scopes) {
        this.currentUsers = currentUsers;
        this.scopes = scopes;
    }

    public boolean allows(CurrentUser user, Capability capability) {
        if (capability == Capability.TIMETABLE_IMPORT) return user.roles().contains("ADMIN")
                && user.authorizationScopes().stream().anyMatch(g -> "ADMIN".equals(g.roleCode())
                && AuthorizedScopeProvider.isGlobalReader(g));
        var scope = scopes.resolve(user, AuthorizedScopeProvider.Purpose.READ);
        return scope.global() || !scope.teacherIds().isEmpty() || !scope.offeringIds().isEmpty();
    }

    public void requireTimetableImport() {
        requireTimetableImport(currentUsers.requireCurrentUser());
    }

    public void requireTimetableImport(CurrentUser user) {
        if (!allows(user, Capability.TIMETABLE_IMPORT))
            throw new AccessDeniedException("TIMETABLE_IMPORT required");
    }

    public void requireCourseRead(CurrentUser user) {
        if (!allows(user, Capability.COURSE_READ))
            throw new AccessDeniedException("COURSE_READ required");
    }

    public void requireClassroomRead(CurrentUser user) {
        if (!allows(user, Capability.CLASSROOM_READ))
            throw new AccessDeniedException("CLASSROOM_READ required");
    }

    public void requireClassroomWrite(CurrentUser user) {
        if (!allows(user, Capability.CLASSROOM_WRITE))
            throw new AccessDeniedException("CLASSROOM_WRITE required");
    }
}
