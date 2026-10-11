package com.aijiaoxue.identity.authentication;

import com.aijiaoxue.identity.persistence.AccountMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final AccountMapper accounts;
    public DatabaseUserDetailsService(AccountMapper accounts) { this.accounts = accounts; }
    @Override public UserDetails loadUserByUsername(String username) {
        var account = accounts.credentials(username);
        if (account == null) throw new UsernameNotFoundException("Invalid credentials");
        return new AccountPrincipal(account.id(), account.username(), account.passwordHash(), account.enabled());
    }
}
