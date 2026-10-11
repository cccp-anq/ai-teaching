package com.aijiaoxue.course.web;

import com.aijiaoxue.course.comparison.TeacherComparisonResult;
import com.aijiaoxue.course.comparison.TeacherComparisonService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TeacherComparisonController {
    private final TeacherComparisonService comparisons;

    public TeacherComparisonController(TeacherComparisonService comparisons) {
        this.comparisons = comparisons;
    }

    @GetMapping("/teachers/comparison")
    TeacherComparisonResult compare(@RequestParam(required = false) List<Long> teacherIds) {
        return comparisons.compare(teacherIds);
    }
}
