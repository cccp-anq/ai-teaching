package com.aijiaoxue.supervision.coverage;

import com.aijiaoxue.identity.OfferingScope;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * The single coverage predicate home: covered means "at least one COMPLETED evaluation".
 * Summary and both drilldowns call the same SQL fragments, so counts can never drift.
 */
@Mapper
public interface SupervisionCoverageMapper {
    List<CoverageSnapshotRow> selectSnapshot(@Param("scope") OfferingScope scope,
                                             @Param("semesterId") Long semesterId,
                                             @Param("ownerCollegeId") Long ownerCollegeId);

    long countEligible(@Param("scope") OfferingScope scope,
                       @Param("semesterId") Long semesterId,
                       @Param("ownerCollegeId") Long ownerCollegeId);

    long countCovered(@Param("scope") OfferingScope scope,
                      @Param("semesterId") Long semesterId,
                      @Param("ownerCollegeId") Long ownerCollegeId);

    List<CoverageOfferingRow> selectDrilldown(@Param("scope") OfferingScope scope,
                                              @Param("semesterId") Long semesterId,
                                              @Param("ownerCollegeId") Long ownerCollegeId,
                                              @Param("covered") boolean covered);
}
