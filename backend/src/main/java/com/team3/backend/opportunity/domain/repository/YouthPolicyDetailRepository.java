package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.YouthPolicyDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface YouthPolicyDetailRepository extends JpaRepository<YouthPolicyDetail, Long> {

    Optional<YouthPolicyDetail> findByVersionId(Long versionId);

    List<YouthPolicyDetail> findByVersionIdIn(Collection<Long> versionIds);
}
