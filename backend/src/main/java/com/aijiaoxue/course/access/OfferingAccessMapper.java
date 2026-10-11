package com.aijiaoxue.course.access;

import com.aijiaoxue.course.query.CourseOfferingQueryCriteria;
import com.aijiaoxue.identity.OfferingScope;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface OfferingAccessMapper {
    List<OfferingReference> select(@Param("scope") OfferingScope scope, @Param("id") Long id,
                                  @Param("teacherId") Long teacherId, @Param("sourceCollegeId") Long sourceCollegeId);

    long countSearch(@Param("scope") OfferingScope scope,
                     @Param("criteria") CourseOfferingQueryCriteria criteria);

    List<OfferingQueryRow> selectSearchPage(@Param("scope") OfferingScope scope,
                                            @Param("criteria") CourseOfferingQueryCriteria criteria,
                                            @Param("offset") long offset,
                                            @Param("limit") int limit);

    List<OfferingListScheduleRow> selectListSchedules(@Param("offeringIds") List<Long> offeringIds);

    OfferingDetailRow selectDetailBase(@Param("scope") OfferingScope scope,
                                       @Param("offeringId") long offeringId);

    List<OfferingScheduleRow> selectDetailSchedules(@Param("offeringId") long offeringId);

    List<ScheduleWeekRow> selectDetailWeeks(@Param("scheduleIds") List<Long> scheduleIds);

    List<ScheduleTeacherRow> selectDetailTeachers(@Param("scheduleIds") List<Long> scheduleIds);

    List<ComparisonTeacherRow> selectComparisonTeachers(@Param("scope") OfferingScope scope,
                                                         @Param("teacherIds") List<Long> teacherIds);

    List<ComparisonOfferingRow> selectComparisonOfferings(@Param("scope") OfferingScope scope,
                                                          @Param("teacherIds") List<Long> teacherIds);

    List<ComparisonScheduleRow> selectComparisonSchedules(@Param("scope") OfferingScope scope,
                                                          @Param("teacherIds") List<Long> teacherIds);
}
