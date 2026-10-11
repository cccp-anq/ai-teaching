package com.aijiaoxue.course.web;

import com.aijiaoxue.common.api.PageResult;
import com.aijiaoxue.course.query.CourseOfferingListItem;
import com.aijiaoxue.course.query.CourseOfferingQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CourseOfferingQueryController {
    private final CourseOfferingQueryService queries;

    public CourseOfferingQueryController(CourseOfferingQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/course-offerings")
    PageResult<CourseOfferingListItem> search(@Valid @ModelAttribute CourseOfferingQueryRequest request) {
        return queries.search(request.toCriteria());
    }
}
