package com.aijiaoxue.course.web;

import com.aijiaoxue.course.schedule.MyScheduleService;
import com.aijiaoxue.course.schedule.MyScheduleView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MyScheduleController {
    private final MyScheduleService schedules;

    public MyScheduleController(MyScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping("/my-schedule")
    MyScheduleView get() {
        return schedules.get();
    }
}
