package com.aijiaoxue.classroom;

import java.time.LocalDateTime;

/**
 * S2-05: a teacher learns that an official (supervisor-confirmed) report is available.
 * A notification always belongs to one recipient and is never listed for anybody else.
 */
public record TeacherReportNotification(long notificationId, long mediaId, long offeringId,
                                       String kind, String state, LocalDateTime createdAt,
                                       LocalDateTime readAt) {
    public static final String KIND_REPORT_PUBLISHED = "REPORT_PUBLISHED";
    public static final String STATE_UNREAD = "UNREAD";
    public static final String STATE_READ = "READ";
}
