package com.aijiaoxue.course.web;

import com.aijiaoxue.common.api.PageQuery;
import com.aijiaoxue.course.query.CourseOfferingQueryCriteria;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

public class CourseOfferingQueryRequest extends PageQuery {
    @Positive private Long semesterId;
    private String courseName;
    private String courseCode;
    private String teacher;
    @Positive private Long sourceScopeCollegeId;
    @Positive private Long ownerCollegeId;
    @Min(1) @Max(18) private Integer week;
    @Min(1) @Max(7) private Integer weekday;
    @Min(1) @Max(Short.MAX_VALUE) private Integer section;
    private String location;

    CourseOfferingQueryCriteria toCriteria() {
        return new CourseOfferingQueryCriteria(semesterId, courseName, courseCode, teacher,
                sourceScopeCollegeId, ownerCollegeId, week, weekday, section, location, getPage(), getSize());
    }

    public Long getSemesterId() { return semesterId; }
    public void setSemesterId(Long semesterId) { this.semesterId = semesterId; }
    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }
    public String getCourseCode() { return courseCode; }
    public void setCourseCode(String courseCode) { this.courseCode = courseCode; }
    public String getTeacher() { return teacher; }
    public void setTeacher(String teacher) { this.teacher = teacher; }
    public Long getSourceScopeCollegeId() { return sourceScopeCollegeId; }
    public void setSourceScopeCollegeId(Long sourceScopeCollegeId) { this.sourceScopeCollegeId = sourceScopeCollegeId; }
    public Long getOwnerCollegeId() { return ownerCollegeId; }
    public void setOwnerCollegeId(Long ownerCollegeId) { this.ownerCollegeId = ownerCollegeId; }
    public Integer getWeek() { return week; }
    public void setWeek(Integer week) { this.week = week; }
    public Integer getWeekday() { return weekday; }
    public void setWeekday(Integer weekday) { this.weekday = weekday; }
    public Integer getSection() { return section; }
    public void setSection(Integer section) { this.section = section; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
