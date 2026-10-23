package com.team3.notification.fixture;

import org.springframework.data.jpa.repository.JpaRepository;

/** Exists only on the test classpath. */
public interface BoundaryProbeRepository extends JpaRepository<BoundaryProbe, Long> {
}
