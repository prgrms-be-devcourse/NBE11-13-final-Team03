package com.team3.backend.opportunity.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * 조건 묶음: 판정 엔진이 기회 버전에서 읽는 입력(자격 조건 행 + 거주지역 코드)이 같은 버전들의 묶음.
 * 같은 묶음이면 같은 사용자에게 판정 결과가 같으므로, 판정은 묶음마다 한 번만 하면 된다.
 * 실데이터(2026-10-21): 직업훈련 137,820 버전 → 묶음 3, 청년정책 3,416 → 약 1,830 (지역 포함).
 *
 * 키(conditionHash)는 ConditionSetKey가 만든 SHA-256이다. 사용자와 무관하며 생성 후 바뀌지 않는다.
 */
@Entity
@Immutable
@Table(
        name = "condition_sets",
        uniqueConstraints = @UniqueConstraint(name = "uk_condition_sets_hash", columnNames = "condition_hash")
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConditionSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ConditionSetKey.hash 결과 (SHA-256 16진수 64자) */
    @Column(name = "condition_hash", nullable = false, length = 64)
    private String conditionHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
