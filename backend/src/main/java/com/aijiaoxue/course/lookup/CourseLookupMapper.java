package com.aijiaoxue.course.lookup;

import com.aijiaoxue.identity.OfferingScope;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface CourseLookupMapper {
    List<SemesterLookup> selectSemesters(@Param("scope") OfferingScope scope);

    List<CollegeLookup> selectColleges(@Param("scope") OfferingScope scope);

    List<CollegeLookup> selectOwnerColleges(@Param("scope") OfferingScope scope);

    List<TeacherLookup> selectTeachers(@Param("scope") OfferingScope scope);
}
