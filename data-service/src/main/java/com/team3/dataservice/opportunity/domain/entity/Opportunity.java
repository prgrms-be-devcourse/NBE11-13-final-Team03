package com.team3.dataservice.opportunity.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "opportunities")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Opportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "opportunity_type", nullable = false, length = 30)
    private OpportunityType opportunityType;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    /** 대분류 (온통청년 분류 체계 기준) */
    @Column(name = "category", length = 100)
    private String category;

    /** 중분류 (온통청년 분류 체계 기준) */
    @Column(name = "sub_category", length = 100)
    private String subCategory;

    /** 세 번째 분류. 직업훈련: NCS 2차분류(중분류) 이름. 청년정책은 없음(null) */
    @Column(name = "detail_category", length = 100)
    private String detailCategory;

    @Column(name = "detail_url", length = 2000)
    private String detailUrl;

    /**
     * 순환 참조(opportunity <-> opportunity_versions)이므로 nullable.
     * Opportunity INSERT -> OpportunityVersion INSERT -> changeCurrentVersion()로 UPDATE 순서를 따른다.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "current_version_id",
            foreignKey = @ForeignKey(name = "fk_opportunities_current_version")
    )
    private OpportunityVersion currentVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void changeCurrentVersion(OpportunityVersion version) {
        Objects.requireNonNull(version, "version");
        Opportunity owner = version.getOpportunity();
        boolean sameOwner = owner == this
                || (this.id != null && this.id.equals(owner.getId()));
        if (!sameOwner) {
            throw new IllegalArgumentException("다른 Opportunity의 버전은 현재 버전으로 지정할 수 없습니다.");
        }
        this.currentVersion = version;
    }

    public void updateSummary(String title, String category, String subCategory, String detailUrl) {
        updateSummary(title, category, subCategory, null, detailUrl);
    }

    public void updateSummary(String title, String category, String subCategory, String detailCategory,
                              String detailUrl) {
        this.title = Objects.requireNonNull(title, "title");
        this.category = category;
        this.subCategory = subCategory;
        this.detailCategory = detailCategory;
        this.detailUrl = detailUrl;
    }
}
