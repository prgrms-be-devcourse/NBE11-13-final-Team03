package com.team3.dataservice.opportunity.domain.repository;

import com.team3.dataservice.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.dataservice.opportunity.dto.TrainingScheduleRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrainingCourseDetailRepository extends JpaRepository<TrainingCourseDetail, Long> {

    Optional<TrainingCourseDetail> findByVersionId(Long versionId);

    List<TrainingCourseDetail> findByVersionIdIn(Collection<Long> versionIds);

    @Query("select new com.team3.dataservice.opportunity.dto.TrainingScheduleRow(d.version.id, d.ncsCode, d.trainingStartDate) "
            + "from TrainingCourseDetail d where d.version.id in :versionIds")
    List<TrainingScheduleRow> findScheduleRows(@Param("versionIds") Collection<Long> versionIds);
}
