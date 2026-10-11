package com.aijiaoxue.identity.authentication;

import java.util.List;
import org.springframework.security.core.userdetails.User;

/** Session principal contains the authenticated ID, not cached authorization grants. */
public final class AccountPrincipal extends User {
    private final long userId;
    public AccountPrincipal(long userId, String username, String hash, boolean enabled) {
        super(username, hash, enabled, true, true, true, List.of());
        this.userId = userId;
    }
    public long userId() { return userId; }
}
