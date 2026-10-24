package com.team3.notification.domain.repository;

import com.team3.notification.domain.entity.WithdrawnUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WithdrawnUserRepository extends JpaRepository<WithdrawnUser, Long> { }
