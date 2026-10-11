package com.aijiaoxue.course.schedule;

import com.aijiaoxue.course.query.CourseOfferingListItem;
import java.util.List;

/** Personal schedules stay separated by business purpose even for multi-role users. */
public record MyScheduleView(
        List<CourseOfferingListItem> teaching,
        List<CourseOfferingListItem> supervision) {
    public MyScheduleView {
        teaching = List.copyOf(teaching);
        supervision = List.copyOf(supervision);
    }
}
