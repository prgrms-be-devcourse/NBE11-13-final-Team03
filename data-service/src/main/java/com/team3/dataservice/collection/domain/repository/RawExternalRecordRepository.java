package com.team3.dataservice.collection.domain.repository;

import com.team3.dataservice.collection.domain.entity.RawExternalRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawExternalRecordRepository extends JpaRepository<RawExternalRecord, Long> {
    Optional<RawExternalRecord> findFirstBySourceIdAndExternalIdOrderByIdDesc(Long sourceId, String externalId);
}
