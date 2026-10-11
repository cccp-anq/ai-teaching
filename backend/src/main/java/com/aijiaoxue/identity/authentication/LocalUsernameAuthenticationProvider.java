package com.aijiaoxue.identity.authentication;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

/** Explicit local testing mode. The regular password provider remains the default. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "app.identity.username-only-login-enabled", havingValue = "true")
public final class LocalUsernameAuthenticationProvider implements AuthenticationProvider {
    private static final Logger LOG=LoggerFactory.getLogger(LocalUsernameAuthenticationProvider.class);
    private static final String REQUIRED_CONFIRMATION="LOCAL_DEVELOPMENT_ONLY";
    private final UserDetailsService users;

    public LocalUsernameAuthenticationProvider(UserDetailsService users, Environment environment) {
        String address = environment.getProperty("server.address", "");
        if (!Set.of("127.0.0.1", "::1", "localhost").contains(address)) {
            throw new IllegalStateException("Username-only login requires an explicit loopback server.address");
        }
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException("Username-only login is forbidden in production profiles");
        }
        String confirmation=environment.getProperty("app.identity.username-only-login-confirmation","");
        if(!REQUIRED_CONFIRMATION.equals(confirmation)) {
            throw new IllegalStateException("Username-only login requires the explicit local-development confirmation");
        }
        LOG.warn("SECURITY WARNING: username-only login is enabled; passwords are ignored. "
                +"This mode is restricted to an explicitly confirmed loopback development process.");
        this.users = users;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        var principal = users.loadUserByUsername(authentication.getName());
        if (!principal.isEnabled()) throw new DisabledException("Account disabled");
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
