package com.team3.dataservice.opportunity.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

/**
 * 버전별 분류 (검색 필터·관심 분야 매칭용). 청년정책의 대·중분류를 짝 단위로 나눠 저장한다 (CategoryNormalizer).
 * opportunities.category / sub_category는 원본 문자열 그대로 두고, 여러 분류 판단은 이 테이블로 한다.
 * 직업훈련은 분류가 하나라 행을 만들지 않는다.
 */
@Entity
@Immutable
@Table(
        name = "opportunity_categories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_opportunity_categories_version_order",
                columnNames = {"version_id", "sort_order"}
        ),
        indexes = {
                @Index(name = "idx_opportunity_categories_large", columnList = "large_category"),
                @Index(name = "idx_opportunity_categories_medium", columnList = "medium_category")
        }
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpportunityCategory {

    @Id
    // 묶음 INSERT를 위해 시퀀스를 쓴다 (OpportunityRegion과 같은 방식)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "opportunity_categories_seq")
    @SequenceGenerator(name = "opportunity_categories_seq", sequenceName = "opportunity_categories_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "version_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_opportunity_categories_version")
    )
    private OpportunityVersion version;

    /** 원본에 나온 순서 (0부터) */
    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;

    /** 대분류. 개수가 맞지 않아 중분류만 있는 행이면 null */
    @Column(name = "large_category", length = 100)
    private String largeCategory;

    /** 중분류. 대분류만 있는 행이면 null */
    @Column(name = "medium_category", length = 100)
    private String mediumCategory;
}
