package com.aijiaoxue.identity.authentication;

import com.aijiaoxue.identity.CurrentUser;
import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.persistence.AccountMapper;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SecurityCurrentUserProvider implements CurrentUserProvider {
    private final AccountMapper accounts;
    public SecurityCurrentUserProvider(AccountMapper accounts) { this.accounts = accounts; }

    @Override @Transactional(readOnly = true)
    public CurrentUser requireCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AccountPrincipal principal))
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        var account = accounts.identity(principal.userId());
        if (account == null) throw new AuthenticationCredentialsNotFoundException("Authentication required");
        if (!account.enabled()) throw new DisabledException("Account disabled");
        var grants = accounts.scopes(account.id());
        return new CurrentUser(account.id(), account.username(), account.teacherId(),
                grants.stream().map(g -> g.roleCode()).collect(Collectors.toSet()), grants);
    }
}
