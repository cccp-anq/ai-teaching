package com.aijiaoxue.course.detail;

import com.aijiaoxue.course.access.AuthorizedOfferingRepository;
import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.PermissionService;
import org.springframework.stereotype.Service;

@Service
public class CourseOfferingDetailService {
    private final CurrentUserProvider currentUsers;
    private final PermissionService permissions;
    private final AuthorizedOfferingRepository offerings;

    public CourseOfferingDetailService(CurrentUserProvider currentUsers, PermissionService permissions,
                                       AuthorizedOfferingRepository offerings) {
        this.currentUsers = currentUsers;
        this.permissions = permissions;
        this.offerings = offerings;
    }

    public CourseOfferingDetail get(long offeringId) {
        var currentUser = currentUsers.requireCurrentUser();
        permissions.requireCourseRead(currentUser);
        return offerings.getDetail(currentUser, offeringId);
    }
}
