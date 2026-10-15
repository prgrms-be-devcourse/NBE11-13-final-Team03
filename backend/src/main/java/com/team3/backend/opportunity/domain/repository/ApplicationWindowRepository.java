package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.ApplicationWindow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ApplicationWindowRepository extends JpaRepository<ApplicationWindow, Long> {

    List<ApplicationWindow> findByVersionId(Long versionId);

    List<ApplicationWindow> findByVersionIdIn(Collection<Long> versionIds);
}
