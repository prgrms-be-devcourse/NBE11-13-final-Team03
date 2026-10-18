package com.team3.backend.opportunity.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 외부 출처 레코드와 내부 Opportunity의 매핑.
 * source_id는 수집 영역의 ExternalSource를 가리키지만, 해당 엔티티가 아직 없어
 * 수집 영역과의 결합을 피하기 위해 ID 값으로만 보관한다. (팀 합의 후 연관관계 전환)
 */
@Entity
@Table(
        name = "opportunity_sources",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_opportunity_sources_source_external",
                columnNames = {"source_id", "external_id"}
        ),
        indexes = @Index(name = "idx_opportunity_sources_opportunity", columnList = "opportunity_id")
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpportunitySource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "opportunity_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_opportunity_sources_opportunity")
    )
    private Opportunity opportunity;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "external_id", nullable = false, length = 200)
    private String externalId;

    @Column(name = "source_url", length = 2000)
    private String sourceUrl;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    public void markSeen(LocalDateTime seenAt, String sourceUrl) {
        Objects.requireNonNull(seenAt, "seenAt");
        if (seenAt.isAfter(this.lastSeenAt)) {
            this.lastSeenAt = seenAt;
        }
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            this.sourceUrl = sourceUrl;
        }
    }
}
