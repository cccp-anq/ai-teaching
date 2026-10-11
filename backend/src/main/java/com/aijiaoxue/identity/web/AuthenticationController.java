package com.aijiaoxue.identity.web;

import com.aijiaoxue.identity.CurrentUser;
import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.authentication.LocalUsernameAuthenticationProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthenticationController {
    private final CurrentUserProvider users;
    private final ObjectProvider<LocalUsernameAuthenticationProvider> localLogin;
    public AuthenticationController(CurrentUserProvider users,
                                    ObjectProvider<LocalUsernameAuthenticationProvider> localLogin) {
        this.users = users;
        this.localLogin = localLogin;
    }
    record Token(String token, String headerName, String parameterName) {}
    record LoginMode(boolean usernameOnly) {}
    @GetMapping("/auth/login-mode") LoginMode loginMode() {
        return new LoginMode(localLogin.getIfAvailable() != null);
    }
    @GetMapping("/auth/csrf") Token csrf(CsrfToken csrf) {
        return new Token(csrf.getToken(), csrf.getHeaderName(), csrf.getParameterName());
    }
    @GetMapping("/auth/me") CurrentUser me() { return users.requireCurrentUser(); }
}
