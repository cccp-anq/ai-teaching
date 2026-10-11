package com.aijiaoxue.classroom;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** S2-05: the teacher's own notification list. Never addressable by owner identifier. */
@RestController
public class TeacherNotificationController {
    private final ClassroomReportNotificationService notifications;

    public TeacherNotificationController(ClassroomReportNotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/me/notifications")
    ResponseEntity<List<TeacherReportNotification>> listMine() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(notifications.listMine());
    }

    @PostMapping("/me/notifications/{notificationId}/read")
    ResponseEntity<TeacherReportNotification> markRead(@PathVariable long notificationId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(notifications.markRead(notificationId));
    }
}
