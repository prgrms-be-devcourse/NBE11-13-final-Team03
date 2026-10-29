package com.team3.dataservice.opportunity.domain.entity;

import com.team3.dataservice.region.domain.entity.Region;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

/**
 * 버전별 적용 지역 (검색·매칭용).
 * 지역 조건 유형(NATIONAL/SPECIFIC/UNKNOWN)과 근거 문장은 REGION 조건(OpportunityCondition)에 저장한다.
 * NATIONAL·UNKNOWN이면 행이 없다. 지역명은 저장하지 않고 regions 사전에서 조회한다.
 */
@Entity
@Immutable
@Table(
        name = "opportunity_regions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_opportunity_regions_version_code_role",
                columnNames = {"version_id", "region_code", "region_role"}
        ),
        indexes = @Index(name = "idx_opportunity_regions_code", columnList = "region_code")
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpportunityRegion {

    @Id
    // 묶음 INSERT(hibernate.jdbc.batch_size)를 쓰려면 IDENTITY가 아니라 시퀀스여야 한다. 50개씩 미리 받는다 (pooled)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "opportunity_regions_seq")
    @SequenceGenerator(name = "opportunity_regions_seq", sequenceName = "opportunity_regions_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "version_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_opportunity_regions_version")
    )
    private OpportunityVersion version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "region_code",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_opportunity_regions_region")
    )
    private Region region;

    @Enumerated(EnumType.STRING)
    @Column(name = "region_role", nullable = false, length = 30)
    private RegionRole regionRole;
}
