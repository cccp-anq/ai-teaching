package com.aijiaoxue.identity.config;

import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.authentication.LocalUsernameAuthenticationProvider;
import com.aijiaoxue.identity.web.SecurityErrorWriter;
import com.aijiaoxue.identity.authentication.UsernameOnlyLoginAudit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper, CurrentUserProvider users,
                                   SecurityErrorWriter errors,
                                   UsernameOnlyLoginAudit localLoginAudit,
                                   ObjectProvider<LocalUsernameAuthenticationProvider> localLogin) throws Exception {
        localLogin.ifAvailable(http::authenticationProvider);
        return http.authorizeHttpRequests(r -> r
                .requestMatchers(HttpMethod.GET, "/api/v1/system/status", "/api/v1/auth/csrf", "/api/v1/auth/login-mode").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/classroom-module-media/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/course-offerings", "/api/v1/course-offerings/*", "/api/v1/my-schedule").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/supervision/coverage", "/api/v1/supervision/coverage/view", "/api/v1/supervision/coverage/covered", "/api/v1/supervision/coverage/uncovered").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/teachers/comparison").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/lookups/semesters", "/api/v1/lookups/colleges", "/api/v1/lookups/owner-colleges", "/api/v1/lookups/teachers").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/imports/timetable", "/api/v1/imports/supervision-evaluations").authenticated()
                .requestMatchers("/api/v1/course-offerings/*/class-sessions/**", "/api/v1/class-sessions/**").authenticated()
                // Sprint 2 teacher actions stay owner-scoped inside the service; the security layer only
                // requires an authenticated caller, and a foreign identifier is answered as not-found.
                .requestMatchers("/api/v1/teacher-actions", "/api/v1/teacher-actions/*",
                        "/api/v1/teacher-actions/*/status").authenticated()
                // S2-05 notifications are always the caller's own; ownership is enforced in the service.
                .requestMatchers("/api/v1/me/notifications", "/api/v1/me/notifications/*/read").authenticated()
                .anyRequest().denyAll())
            .requestCache(c -> c.disable())
            .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
            .formLogin(f -> f.loginPage("/api/v1/auth/login").loginProcessingUrl("/api/v1/auth/login")
                .successHandler((req, res, auth) -> {
                    if(localLogin.getIfAvailable()!=null)
                        localLoginAudit.record(users.requireCurrentUser(),req);
                    res.setContentType("application/json");
                    mapper.writeValue(res.getOutputStream(), users.requireCurrentUser());
                })
                // Internal failure types differ; the response does not enumerate accounts.
                .failureHandler((req, res, ex) -> errors.write(req, res, 401, "INVALID_CREDENTIALS")))
            .logout(l -> l.logoutUrl("/api/v1/auth/logout").invalidateHttpSession(true)
                .clearAuthentication(true).deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> errors.write(req, res, 401, "UNAUTHENTICATED"))
                .accessDeniedHandler((req, res, ex) -> errors.write(req, res, 403, "FORBIDDEN")))
            .build();
    }
}
