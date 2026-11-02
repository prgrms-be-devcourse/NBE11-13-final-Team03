package com.team3.backend.opportunity.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

/**
 * 버전별 분류 (검색 필터·관심 분야 매칭용). 청년정책의 대·중분류를 짝 단위로 나눈 행이며 data-service 정규화가 만든다.
 * opportunities.category / sub_category는 원본 문자열 그대로라 청년정책의 분류 판단은 이 테이블로 한다.
 * 직업훈련은 분류가 하나라 행이 없고 기존 컬럼을 쓴다. 대분류만 있거나 중분류만 있는 행도 있다 (개수가 맞지 않는 원본).
 * backend는 읽기만 하며 컬럼·제약은 data-service 엔티티를 따른다.
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
    // data-service와 같은 시퀀스. backend가 먼저 테이블을 만들어도 data-service의 시퀀스 INSERT와 맞는다
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

    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;

    @Column(name = "large_category", length = 100)
    private String largeCategory;

    @Column(name = "medium_category", length = 100)
    private String mediumCategory;
}
