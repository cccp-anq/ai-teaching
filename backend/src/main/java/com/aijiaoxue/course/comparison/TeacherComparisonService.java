package com.aijiaoxue.course.comparison;

import com.aijiaoxue.course.access.AuthorizedOfferingRepository;
import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.PermissionService;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TeacherComparisonService {
    public static final int MAX_TEACHERS = 5;

    private final CurrentUserProvider currentUsers;
    private final PermissionService permissions;
    private final AuthorizedOfferingRepository offerings;

    public TeacherComparisonService(CurrentUserProvider currentUsers, PermissionService permissions,
                                    AuthorizedOfferingRepository offerings) {
        this.currentUsers = currentUsers;
        this.permissions = permissions;
        this.offerings = offerings;
    }

    public TeacherComparisonResult compare(List<Long> teacherIds) {
        var currentUser = currentUsers.requireCurrentUser();
        permissions.requireCourseRead(currentUser);
        return offerings.compareTeachers(currentUser, normalize(teacherIds));
    }

    private static List<Long> normalize(List<Long> teacherIds) {
        if (teacherIds == null || teacherIds.isEmpty())
            throw TeacherComparisonRejectedException.invalidSelection("teacherIds must contain 1 to 5 values");
        var distinct = new LinkedHashSet<Long>();
        for (Long teacherId : teacherIds) {
            if (teacherId == null || teacherId < 1)
                throw TeacherComparisonRejectedException.invalidSelection("teacherIds must be positive integers");
            distinct.add(teacherId);
        }
        if (distinct.size() > MAX_TEACHERS)
            throw TeacherComparisonRejectedException.invalidSelection("teacherIds must contain at most 5 distinct values");
        return List.copyOf(distinct);
    }
}
