package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.ApplicationWindow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.opportunity.normalization.ApplicationStatusCalculator;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;

/**
 * 신청(모집) 상태 DB 필터. ApplicationStatusCalculator와 같은 규칙을 SQL 조건으로 옮긴 것이다 (검색 등에서 페이지네이션·전체 건수를 정확히 내기 위해).
 * 규칙을 바꿀 때는 Calculator와 이 클래스를 함께 바꾸고 ApplicationStatusSpecificationsTest로 일치를 확인한다.
 *
 * 기회의 현재 버전(currentVersion)에 딸린 신청기간 중 검증 상태가 VALID인 것만 본다. 현재 버전이 없으면 UNKNOWN.
 * 우선순위: 열린 기간 있음 → OPEN, 상시 → ALWAYS_OPEN, 향후 기간 → UPCOMING, 종료·마감 → CLOSED, 나머지 → UNKNOWN
 *
 * 기준일(today)은 한국 시간 날짜를 넘긴다 (DB의 현재 시각을 쓰지 않는다).
 * 사용 예: opportunityRepository.findAll(where(keyword).and(ApplicationStatusSpecifications.statusIn(List.of(OPEN, ALWAYS_OPEN), today)), pageable)
 *
 * @see ApplicationStatusCalculator
 */
public final class ApplicationStatusSpecifications {

    private ApplicationStatusSpecifications() {
    }

    /** 신청 상태가 statuses 중 하나인 기회 */
    public static Specification<Opportunity> statusIn(Collection<ApplicationStatus> statuses, LocalDate today) {
        Objects.requireNonNull(statuses, "statuses");
        Objects.requireNonNull(today, "today");
        return (root, query, cb) -> cb.or(statuses.stream()
                .distinct()
                .map(s -> status(s, root, query, cb, today))
                .toArray(Predicate[]::new));
    }

    /** 신청 상태가 status인 기회 */
    public static Specification<Opportunity> hasStatus(ApplicationStatus status, LocalDate today) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(today, "today");
        return (root, query, cb) -> status(status, root, query, cb, today);
    }

    private static Predicate status(ApplicationStatus status, Root<Opportunity> root, CriteriaQuery<?> query,
                                    CriteriaBuilder cb, LocalDate today) {
        Predicate open = exists(root, query, cb, (w, b) -> open(w, b, today));
        Predicate always = exists(root, query, cb, (w, b) -> b.equal(w.get("periodType"), PeriodType.ALWAYS));
        Predicate upcoming = exists(root, query, cb, (w, b) -> upcoming(w, b, today));
        Predicate closed = exists(root, query, cb, (w, b) -> closed(w, b, today));
        return switch (status) {
            case OPEN -> open;
            case ALWAYS_OPEN -> cb.and(cb.not(open), always);
            case UPCOMING -> cb.and(cb.not(open), cb.not(always), upcoming);
            case CLOSED -> cb.and(cb.not(open), cb.not(always), cb.not(upcoming), closed);
            case UNKNOWN -> cb.and(cb.not(open), cb.not(always), cb.not(upcoming), cb.not(closed));
        };
    }

    /** FIXED 기간이 오늘을 포함 (시작일만 있으면 시작 후 계속, 종료일만 있으면 종료일까지). 두 날짜가 모두 없으면 제외 */
    private static Predicate open(Root<ApplicationWindow> w, CriteriaBuilder cb, LocalDate today) {
        Path<LocalDate> start = w.get("startDate");
        Path<LocalDate> end = w.get("endDate");
        return cb.and(
                cb.equal(w.get("periodType"), PeriodType.FIXED),
                cb.or(start.isNotNull(), end.isNotNull()),
                cb.or(start.isNull(), cb.lessThanOrEqualTo(start, today)),
                cb.or(end.isNull(), cb.greaterThanOrEqualTo(end, today)));
    }

    /** FIXED 기간의 시작일이 오늘 이후 (Calculator는 시작 전 여부를 종료 여부보다 먼저 본다) */
    private static Predicate upcoming(Root<ApplicationWindow> w, CriteriaBuilder cb, LocalDate today) {
        Path<LocalDate> start = w.get("startDate");
        return cb.and(cb.equal(w.get("periodType"), PeriodType.FIXED), start.isNotNull(), cb.greaterThan(start, today));
    }

    /** CLOSED 기간, 또는 이미 시작했고(시작일 없음 포함) 종료일이 지난 FIXED 기간 */
    private static Predicate closed(Root<ApplicationWindow> w, CriteriaBuilder cb, LocalDate today) {
        Path<LocalDate> start = w.get("startDate");
        Path<LocalDate> end = w.get("endDate");
        return cb.or(
                cb.equal(w.get("periodType"), PeriodType.CLOSED),
                cb.and(cb.equal(w.get("periodType"), PeriodType.FIXED),
                        end.isNotNull(), cb.lessThan(end, today),
                        cb.or(start.isNull(), cb.lessThanOrEqualTo(start, today))));
    }

    private interface WindowCondition {
        Predicate apply(Root<ApplicationWindow> window, CriteriaBuilder cb);
    }

    /** 현재 버전에 조건을 만족하는 VALID 신청기간이 있는가 */
    private static Predicate exists(Root<Opportunity> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                    WindowCondition condition) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<ApplicationWindow> w = sub.from(ApplicationWindow.class);
        sub.select(w.get("id")).where(
                cb.equal(w.get("version"), root.get("currentVersion")),
                cb.equal(w.get("validationStatus"), ValidationStatus.VALID),
                condition.apply(w, cb));
        return cb.exists(sub);
    }
}
