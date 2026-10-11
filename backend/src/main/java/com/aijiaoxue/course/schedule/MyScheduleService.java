package com.aijiaoxue.course.schedule;

import com.aijiaoxue.course.access.AuthorizedOfferingRepository;
import com.aijiaoxue.identity.AuthorizedScopeProvider;
import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.PermissionService;
import org.springframework.stereotype.Service;

@Service
public class MyScheduleService {
    private final CurrentUserProvider currentUsers;
    private final PermissionService permissions;
    private final AuthorizedOfferingRepository offerings;

    public MyScheduleService(CurrentUserProvider currentUsers, PermissionService permissions,
                             AuthorizedOfferingRepository offerings) {
        this.currentUsers = currentUsers;
        this.permissions = permissions;
        this.offerings = offerings;
    }

    public MyScheduleView get() {
        var user = currentUsers.requireCurrentUser();
        permissions.requireCourseRead(user);
        return new MyScheduleView(
                offerings.listByPurpose(user, AuthorizedScopeProvider.Purpose.TEACHING),
                offerings.listByPurpose(user, AuthorizedScopeProvider.Purpose.SUPERVISION));
    }
}
