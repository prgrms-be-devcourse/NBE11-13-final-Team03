package com.team3.backend.opportunity.dto;

import com.team3.backend.opportunity.domain.entity.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * GET /api/admin/opportunities/{id}/normalization 응답.
 * 현재 버전의 정규화 결과(조건·신청기간·지역)와 검토가 필요한 항목 수, 출처, 버전 이력을 보여준다.
 * 아직 정규화되지 않은 기회는 currentVersion이 null이고 목록이 비어 있다.
 */
public record NormalizationResultResponse(
        Long opportunityId,
        OpportunityType opportunityType,
        String title,
        String category,
        String detailUrl,
        CurrentVersion currentVersion,
        List<Condition> conditions,
        List<Window> applicationWindows,
        List<RegionItem> regions,
        ReviewSummary reviewSummary,
        List<Source> sources,
        List<VersionHistory> versions
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record CurrentVersion(
            Long versionId,
            Integer versionNo,
            String normalizationVersion,
            PersonalizationStatus personalizationStatus,
            OffsetDateTime createdAt,
            Map<String, Object> normalizedSnapshot
    ) {
        public static CurrentVersion from(OpportunityVersion v) {
            return new CurrentVersion(v.getId(), v.getVersionNo(), v.getNormalizationVersion(),
                    v.getPersonalizationStatus(), kst(v.getCreatedAt()), v.getNormalizedSnapshot());
        }
    }

    public record Condition(
            Long conditionId,
            ConditionType conditionType,
            RequirementType requirementType,
            String conditionField,
            Map<String, Object> expression,
            Map<String, Object> evidence,
            ValidationStatus validationStatus
    ) {
        public static Condition from(OpportunityCondition c) {
            return new Condition(c.getId(), c.getConditionType(), c.getRequirementType(), c.getConditionField(),
                    c.getExpression(), c.getEvidence(), c.getValidationStatus());
        }
    }

    public record Window(
            Long windowId,
            PeriodType periodType,
            LocalDate startDate,
            LocalDate endDate,
            String periodText,
            Map<String, Object> scope,
            ValidationStatus validationStatus
    ) {
        public static Window from(ApplicationWindow w) {
            return new Window(w.getId(), w.getPeriodType(), w.getStartDate(), w.getEndDate(), w.getPeriodText(),
                    w.getScope(), w.getValidationStatus());
        }
    }

    public record RegionItem(String regionCode, String regionName, RegionRole regionRole) {
    }

    /** 검증 상태별 개수 (조건 + 신청기간). VALID는 제외한다 */
    public record ReviewSummary(
            long needsReview,
            long dataConflict,
            long invalid,
            long unknown
    ) {
        public static ReviewSummary of(List<ValidationStatus> statuses) {
            return new ReviewSummary(
                    count(statuses, ValidationStatus.NEEDS_REVIEW),
                    count(statuses, ValidationStatus.DATA_CONFLICT),
                    count(statuses, ValidationStatus.INVALID),
                    count(statuses, ValidationStatus.UNKNOWN));
        }

        private static long count(List<ValidationStatus> statuses, ValidationStatus target) {
            return statuses.stream().filter(s -> s == target).count();
        }
    }

    public record Source(
            Long sourceId,
            String externalId,
            String sourceUrl,
            OffsetDateTime firstSeenAt,
            OffsetDateTime lastSeenAt
    ) {
        public static Source from(OpportunitySource s) {
            return new Source(s.getSourceId(), s.getExternalId(), s.getSourceUrl(),
                    kst(s.getFirstSeenAt()), kst(s.getLastSeenAt()));
        }
    }

    public record VersionHistory(Long versionId, Integer versionNo, String normalizationVersion,
                                 OffsetDateTime createdAt) {
        public static VersionHistory from(OpportunityVersion v) {
            return new VersionHistory(v.getId(), v.getVersionNo(), v.getNormalizationVersion(), kst(v.getCreatedAt()));
        }
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(KST).toOffsetDateTime();
    }
}
