package com.team3.backend.interest.domain.repository;

import com.team3.backend.interest.domain.entity.UserInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserInterestRepository extends JpaRepository<UserInterest, Long> {

    List<UserInterest> findAllByUserIdOrderByInterestTypeAscInterestCodeAsc(Long userId);
}