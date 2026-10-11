package com.aijiaoxue.course.lookup;

import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.AuthorizedScopeProvider;
import com.aijiaoxue.identity.PermissionService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration-support lookup for Sprint 1 frontend selection sources. Purely read-only
 * organizational dimensions; it never grants authorization and never exposes teachers
 * as a directory.
 */
@Service
public class CourseLookupService {
    private final CurrentUserProvider currentUsers;
    private final PermissionService permissions;
    private final AuthorizedScopeProvider scopes;
    private final CourseLookupMapper lookups;

    public CourseLookupService(CurrentUserProvider currentUsers, PermissionService permissions,
                               AuthorizedScopeProvider scopes, CourseLookupMapper lookups) {
        this.currentUsers = currentUsers;
        this.permissions = permissions;
        this.scopes = scopes;
        this.lookups = lookups;
    }

    @Transactional(readOnly = true)
    public List<SemesterLookup> semesters() {
        var currentUser = currentUsers.requireCurrentUser();
        permissions.requireCourseRead(currentUser);
        return List.copyOf(lookups.selectSemesters(
                scopes.resolve(currentUser, AuthorizedScopeProvider.Purpose.READ)));
    }

    @Transactional(readOnly = true)
    public List<CollegeLookup> colleges() {
        var currentUser = currentUsers.requireCurrentUser();
        permissions.requireCourseRead(currentUser);
        return List.copyOf(lookups.selectColleges(
                scopes.resolve(currentUser, AuthorizedScopeProvider.Purpose.READ)));
    }

    /**
     * Teacher display identities within the authorized dataset only. Selection source for the
     * five-teacher comparison; never a full directory and never an authorization grant.
     */
    @Transactional(readOnly = true)
    public List<TeacherLookup> teachers() {
        var currentUser = currentUsers.requireCurrentUser();
        permissions.requireCourseRead(currentUser);
        return List.copyOf(lookups.selectTeachers(
                scopes.resolve(currentUser, AuthorizedScopeProvider.Purpose.READ)));
    }

    /** Formal owner-college options (Offering → Course → owner_college_id) within the authorized dataset. */
    @Transactional(readOnly = true)
    public List<CollegeLookup> ownerColleges() {
        var currentUser = currentUsers.requireCurrentUser();
        permissions.requireCourseRead(currentUser);
        return List.copyOf(lookups.selectOwnerColleges(
                scopes.resolve(currentUser, AuthorizedScopeProvider.Purpose.READ)));
    }
}
