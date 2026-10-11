package com.aijiaoxue.identity;

import java.util.List;
import java.util.Set;

/** Trusted, immutable snapshot. Never deserialize this type from a business request. */
public record CurrentUser(long userId, String username, Long teacherId, Set<String> roles,
                          List<AuthorizationScope> authorizationScopes) {
    public CurrentUser {
        roles = Set.copyOf(roles);
        authorizationScopes = List.copyOf(authorizationScopes);
    }
}
