package com.aijiaoxue.action;

import java.time.LocalDateTime;

/** A teacher's own action. It is never returned for another owner (PRIV3-01). */
public record TeacherActionView(long actionId, Long mediaId, String sourceKind, Integer sourceIndex,
                                String title, String content, String status, int revision,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {}
