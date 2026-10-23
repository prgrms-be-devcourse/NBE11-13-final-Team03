package com.team3.backend.behavior.domain.repository;

import com.team3.backend.behavior.domain.entity.UserBehaviorEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** 사용자 행동 이벤트의 영속화를 담당한다. */
public interface UserBehaviorEventRepository extends JpaRepository<UserBehaviorEvent, Long> {
}
