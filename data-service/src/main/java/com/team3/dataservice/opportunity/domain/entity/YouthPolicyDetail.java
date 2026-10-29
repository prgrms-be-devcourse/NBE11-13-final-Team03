package com.team3.dataservice.opportunity.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

/**
 * 온통청년 청년정책의 버전별 상세 정보 (OpportunityVersion과 1:1, 불변). 공고 상세 화면용.
 * 자격 조건은 OpportunityCondition, 신청기간은 ApplicationWindow, 거주지역은 OpportunityRegion에 둔다.
 *
 * 원문 텍스트는 줄바꿈을 살려 앞뒤 공백만 제거해 저장한다. 빈 값은 NULL.
 * 사업기간(bizPrd*)은 사업 운영기간이며 신청기간이 아니다.
 * 날짜는 숫자만 모아 앞 8자리를 yyyyMMdd로 읽는다. 읽을 수 없으면 NULL.
 * lastMdfcnDt(최종수정일시)는 휘발성 메타데이터라 버전 비교 대상이 될 수 없어 저장하지 않는다.
 * 담당자명(sprvsnInstPicNm, operInstPicNm)은 개인정보라 저장하지 않는다.
 */
@Entity
@Immutable
@Table(
        name = "youth_policy_details",
        uniqueConstraints = @UniqueConstraint(name = "uk_youth_policy_details_version", columnNames = "version_id")
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YouthPolicyDetail {

    @Id
    // 묶음 INSERT(hibernate.jdbc.batch_size)를 쓰려면 IDENTITY가 아니라 시퀀스여야 한다. 50개씩 미리 받는다 (pooled)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "youth_policy_details_seq")
    @SequenceGenerator(name = "youth_policy_details_seq", sequenceName = "youth_policy_details_seq", allocationSize = 50)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "version_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_youth_policy_details_version")
    )
    private OpportunityVersion version;

    /** plcyNo 정책번호 */
    @Column(name = "policy_no", nullable = false, length = 50)
    private String policyNo;

    /** plcyExplnCn 정책설명 */
    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** plcySprtCn 정책지원내용 */
    @Column(name = "support_content", columnDefinition = "text")
    private String supportContent;

    /** plcyKywdNm 정책키워드 (원본 그대로, 쉼표 구분) */
    @Column(name = "keywords", columnDefinition = "text")
    private String keywords;

    /** sprvsnInstCdNm 주관기관명 */
    @Column(name = "supervising_institution_name", length = 500)
    private String supervisingInstitutionName;

    /** operInstCdNm 운영기관명 */
    @Column(name = "operating_institution_name", length = 500)
    private String operatingInstitutionName;

    /** plcyAplyMthdCn 정책신청방법내용 */
    @Column(name = "application_method", columnDefinition = "text")
    private String applicationMethod;

    /** srngMthdCn 심사방법내용 */
    @Column(name = "screening_method", columnDefinition = "text")
    private String screeningMethod;

    /** sbmsnDcmntCn 제출서류내용 */
    @Column(name = "submission_documents", columnDefinition = "text")
    private String submissionDocuments;

    /** aplyUrlAddr 신청URL주소 */
    @Column(name = "application_url", length = 2000)
    private String applicationUrl;

    /** refUrlAddr1 참고URL주소1 */
    @Column(name = "reference_url1", length = 2000)
    private String referenceUrl1;

    /** refUrlAddr2 참고URL주소2 */
    @Column(name = "reference_url2", length = 2000)
    private String referenceUrl2;

    /** bizPrdBgngYmd 사업기간시작일자 */
    @Column(name = "business_start_date")
    private LocalDate businessStartDate;

    /** bizPrdEndYmd 사업기간종료일자 */
    @Column(name = "business_end_date")
    private LocalDate businessEndDate;

    /** bizPrdEtcCn 사업기간기타내용 */
    @Column(name = "business_period_text", columnDefinition = "text")
    private String businessPeriodText;

    /** sprtSclCnt 지원규모수 (원본 그대로. "0"의 의미는 sprtSclLmtYn 코드표 확인 전) */
    @Column(name = "support_scale", length = 100)
    private String supportScale;

    /** etcMttrCn 기타사항내용 */
    @Column(name = "other_notes", columnDefinition = "text")
    private String otherNotes;

    /** frstRegDt 최초등록일시 (원 출처 등록일, 날짜만) */
    @Column(name = "first_registered_date")
    private LocalDate firstRegisteredDate;
}
