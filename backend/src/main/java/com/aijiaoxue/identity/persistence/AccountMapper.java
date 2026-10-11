package com.aijiaoxue.identity.persistence;

import com.aijiaoxue.identity.AuthorizationScope;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface AccountMapper {
    record Credentials(long id, String username, String passwordHash, boolean enabled) {}
    record Identity(long id, String username, Long teacherId, boolean enabled) {}

    @Select("SELECT id,username,password_hash AS passwordHash,enabled FROM user_account WHERE username=#{username}")
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    Credentials credentials(String username);

    @Select("SELECT id,username,teacher_id AS teacherId,enabled FROM user_account WHERE id=#{id}")
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    Identity identity(long id);

    @Select("SELECT r.role_code AS roleCode,s.scope_type AS scopeType,s.college_id AS collegeId,s.teacher_id AS teacherId,s.offering_id AS offeringId FROM user_role_scope s JOIN role r ON r.id=s.role_id WHERE s.user_id=#{id} ORDER BY s.id")
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    List<AuthorizationScope> scopes(long id);
}
