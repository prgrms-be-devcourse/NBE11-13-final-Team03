package com.team3.dataservice.opportunity.domain.repository;

import com.team3.dataservice.opportunity.domain.entity.OpportunityRegion;
import com.team3.dataservice.opportunity.domain.entity.RegionRole;
import com.team3.dataservice.opportunity.dto.RegionCodeRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OpportunityRegionRepository extends JpaRepository<OpportunityRegion, Long> {

    List<OpportunityRegion> findByVersionId(Long versionId);

    List<OpportunityRegion> findByVersionIdIn(Collection<Long> versionIds);

    /** 판정용: 역할별 지역 코드만 */
    @Query("select new com.team3.dataservice.opportunity.dto.RegionCodeRow(r.version.id, r.region.regionCode) "
            + "from OpportunityRegion r where r.version.id in :versionIds and r.regionRole = :role")
    List<RegionCodeRow> findCodesByVersionIdInAndRole(@Param("versionIds") Collection<Long> versionIds,
                                                      @Param("role") RegionRole role);
}
