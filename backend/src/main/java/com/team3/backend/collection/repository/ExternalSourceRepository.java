package com.team3.backend.collection.repository;

import com.team3.backend.collection.domain.ExternalSource;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExternalSourceRepository extends JpaRepository<ExternalSource, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ExternalSource s where s.id = :id")
    Optional<ExternalSource> findByIdForUpdate(@Param("id") Long id);
}
