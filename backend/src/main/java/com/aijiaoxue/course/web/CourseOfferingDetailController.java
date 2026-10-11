package com.aijiaoxue.course.web;

import com.aijiaoxue.course.detail.CourseOfferingDetail;
import com.aijiaoxue.course.detail.CourseOfferingDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CourseOfferingDetailController {
    private final CourseOfferingDetailService details;

    public CourseOfferingDetailController(CourseOfferingDetailService details) {
        this.details = details;
    }

    @GetMapping("/course-offerings/{offeringId}")
    CourseOfferingDetail get(@PathVariable long offeringId) {
        return details.get(offeringId);
    }
}
