package com.aijiaoxue.action;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teacher-owned action endpoints. Every one of them is scoped to the authenticated user;
 * none of them accepts an owner identifier, so a caller cannot address somebody else's row.
 */
@RestController
public class TeacherActionController {
    private final TeacherActionService service;

    public TeacherActionController(TeacherActionService service) { this.service = service; }

    @GetMapping("/teacher-actions")
    ResponseEntity<List<TeacherActionView>> listMine() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(service.listMine());
    }

    @PostMapping(value = "/teacher-actions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TeacherActionView> create(@RequestBody CreateActionRequest request) {
        var created = service.create(request.mediaId(), request.sourceKind(), request.sourceIndex(),
                request.title(), request.content());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(created);
    }

    @PutMapping(value = "/teacher-actions/{actionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TeacherActionView> update(@PathVariable long actionId, @RequestBody UpdateActionRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(service.updateContent(actionId, request.title(), request.content()));
    }

    @PostMapping(value = "/teacher-actions/{actionId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TeacherActionView> changeStatus(@PathVariable long actionId,
                                                   @RequestBody ChangeStatusRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(service.changeStatus(actionId, request.status()));
    }

    record CreateActionRequest(Long mediaId, String sourceKind, Integer sourceIndex, String title, String content) {}
    record UpdateActionRequest(String title, String content) {}
    record ChangeStatusRequest(String status) {}
}
