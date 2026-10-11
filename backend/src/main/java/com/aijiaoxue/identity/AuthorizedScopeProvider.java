package com.aijiaoxue.identity;

import java.util.HashSet;
import org.springframework.stereotype.Component;

@Component
public class AuthorizedScopeProvider {
    public enum Purpose { READ, TEACHING, SUPERVISION }

    public OfferingScope resolve(CurrentUser user, Purpose purpose) {
        boolean global = false;
        var teachers = new HashSet<Long>();
        var offerings = new HashSet<Long>();
        for (var grant : user.authorizationScopes()) {
            if (!user.roles().contains(grant.roleCode())) continue;
            if (purpose == Purpose.READ && isGlobalReader(grant)) global = true;
            if (purpose != Purpose.SUPERVISION && "TEACHER".equals(grant.roleCode())
                    && "SELF_TEACHER".equals(grant.scopeType()) && user.teacherId() != null
                    && user.teacherId().equals(grant.teacherId())
                    && grant.collegeId() == null && grant.offeringId() == null) teachers.add(user.teacherId());
            if (purpose != Purpose.TEACHING && "SUPERVISOR".equals(grant.roleCode())
                    && "OFFERING".equals(grant.scopeType()) && grant.offeringId() != null
                    && grant.collegeId() == null && grant.teacherId() == null) offerings.add(grant.offeringId());
        }
        return new OfferingScope(global, teachers, offerings);
    }

    static boolean isGlobalReader(AuthorizationScope grant) {
        return ("ADMIN".equals(grant.roleCode()) || "DIRECTOR".equals(grant.roleCode()))
                && "GLOBAL".equals(grant.scopeType()) && grant.collegeId() == null
                && grant.teacherId() == null && grant.offeringId() == null;
    }
}
