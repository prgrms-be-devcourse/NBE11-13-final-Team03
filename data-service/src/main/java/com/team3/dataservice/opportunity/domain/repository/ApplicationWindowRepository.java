package com.team3.dataservice.opportunity.domain.repository;

import com.team3.dataservice.opportunity.domain.entity.ApplicationWindow;
import com.team3.dataservice.opportunity.dto.ApplicationWindowRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ApplicationWindowRepository extends JpaRepository<ApplicationWindow, Long> {

    List<ApplicationWindow> findByVersionId(Long versionId);

    List<ApplicationWindow> findByVersionIdIn(Collection<Long> versionIds);

    /** 신청 상태 계산용: scope 제외 */
    @Query("select new com.team3.dataservice.opportunity.dto.ApplicationWindowRow("
            + "w.version.id, w.periodType, w.startDate, w.endDate, w.validationStatus, w.periodText) "
            + "from ApplicationWindow w where w.version.id in :versionIds")
    List<ApplicationWindowRow> findRowsByVersionIdIn(@Param("versionIds") Collection<Long> versionIds);
}
