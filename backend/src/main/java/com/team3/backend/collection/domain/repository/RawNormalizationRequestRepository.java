package com.team3.backend.collection.domain.repository;

import com.team3.backend.collection.domain.entity.RawNormalizationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;

public interface RawNormalizationRequestRepository extends JpaRepository<RawNormalizationRequest, Long> {

    /**
     * Normalization still waiting to run. FAILED is terminal and is not waited on, so an old failure
     * does not delay the date scan until the cutoff every day.
     */
    @Query("""
            select count(q) > 0 from RawNormalizationRequest q
            where q.rawRecord.source.sourceCode = :source
              and q.createdAt < :until and q.status = 'PENDING'
            """)
    boolean hasPendingBefore(@Param("source") String source, @Param("until") OffsetDateTime until);
}
