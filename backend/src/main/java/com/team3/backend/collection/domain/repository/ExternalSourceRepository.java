package com.team3.backend.collection.domain.repository;

import com.team3.backend.collection.domain.entity.ExternalSource;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExternalSourceRepository extends JpaRepository<ExternalSource, Long> {
    Optional<ExternalSource> findBySourceCode(String sourceCode);

    @Modifying
    @Query(value = """
            insert into external_source (source_code, source_name, base_url, enabled, created_at)
            values (:code, :name, :url, true, current_timestamp)
            on conflict (source_code) do nothing
            """, nativeQuery = true)
    void registerIfAbsent(@Param("code") String code, @Param("name") String name, @Param("url") String url);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ExternalSource s where s.id = :id")
    Optional<ExternalSource> findByIdForUpdate(@Param("id") Long id);
}
