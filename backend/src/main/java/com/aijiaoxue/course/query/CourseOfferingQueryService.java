package com.aijiaoxue.course.query;

import com.aijiaoxue.common.api.PageResult;
import com.aijiaoxue.course.access.AuthorizedOfferingRepository;
import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.PermissionService;
import org.springframework.stereotype.Service;

@Service
public class CourseOfferingQueryService {
    private final CurrentUserProvider currentUsers;
    private final PermissionService permissions;
    private final AuthorizedOfferingRepository offerings;

    public CourseOfferingQueryService(CurrentUserProvider currentUsers, PermissionService permissions,
                                      AuthorizedOfferingRepository offerings) {
        this.currentUsers = currentUsers;
        this.permissions = permissions;
        this.offerings = offerings;
    }

    public PageResult<CourseOfferingListItem> search(CourseOfferingQueryCriteria criteria) {
        var currentUser = currentUsers.requireCurrentUser();
        permissions.requireCourseRead(currentUser);
        return offerings.search(currentUser, criteria);
    }
}
