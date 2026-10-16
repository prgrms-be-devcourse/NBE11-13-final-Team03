package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrainingCourseDetailRepository extends JpaRepository<TrainingCourseDetail, Long> {

    Optional<TrainingCourseDetail> findByVersionId(Long versionId);

    List<TrainingCourseDetail> findByVersionIdIn(Collection<Long> versionIds);
}
