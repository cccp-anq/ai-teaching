package com.aijiaoxue.course.web;

import com.aijiaoxue.course.lookup.CollegeLookup;
import com.aijiaoxue.course.lookup.CourseLookupService;
import com.aijiaoxue.course.lookup.SemesterLookup;
import com.aijiaoxue.course.lookup.TeacherLookup;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only selection sources for the Sprint 1 frontend; server-side authorization applies. */
@RestController
public class LookupController {
    private final CourseLookupService lookups;

    public LookupController(CourseLookupService lookups) {
        this.lookups = lookups;
    }

    @GetMapping("/lookups/semesters")
    List<SemesterLookup> semesters() {
        return lookups.semesters();
    }

    @GetMapping("/lookups/colleges")
    List<CollegeLookup> colleges() {
        return lookups.colleges();
    }

    @GetMapping("/lookups/owner-colleges")
    List<CollegeLookup> ownerColleges() {
        return lookups.ownerColleges();
    }

    @GetMapping("/lookups/teachers")
    List<TeacherLookup> teachers() {
        return lookups.teachers();
    }
}
