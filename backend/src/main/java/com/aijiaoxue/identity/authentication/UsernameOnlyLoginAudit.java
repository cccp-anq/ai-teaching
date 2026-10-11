package com.aijiaoxue.identity.authentication;

import com.aijiaoxue.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Persistent evidence for each successful password-bypassing local login. */
@Component
public final class UsernameOnlyLoginAudit {
    private static final Logger LOG=LoggerFactory.getLogger(UsernameOnlyLoginAudit.class);
    private final JdbcTemplate jdbc;

    public UsernameOnlyLoginAudit(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public void record(CurrentUser user,HttpServletRequest request) {
        String remote=truncate(request.getRemoteAddr(),64);
        String agent=truncate(request.getHeader("User-Agent"),255);
        jdbc.update("""
                INSERT INTO username_only_login_audit(user_account_id,username,remote_address,user_agent)
                VALUES (?,?,?,?)""",user.userId(),user.username(),remote,agent);
        LOG.warn("Username-only login succeeded for userId={} username={} remoteAddress={}",
                user.userId(),user.username(),remote);
    }

    private static String truncate(String value,int max) {
        return value==null?null:(value.length()<=max?value:value.substring(0,max));
    }
}
