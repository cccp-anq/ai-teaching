package com.aijiaoxue.identity;

public record AuthorizationScope(String roleCode, String scopeType, Long collegeId,
                                 Long teacherId, Long offeringId) {}
