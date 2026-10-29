package com.team3.backend.search.service;

import com.team3.backend.opportunity.domain.entity.ApplicationWindow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.entity.YouthPolicyDetail;
import com.team3.backend.opportunity.domain.repository.ApplicationStatusSpecifications;
import com.team3.backend.search.domain.repository.SearchSqlFunctions;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 관련도 정렬(B2). 검색 품질 실험에서 확정한 tier·bonus를 그대로 옮긴다 (새 신호·가중치 없음).
 *
 * ORDER BY tier DESC, bonus DESC, 모집 상태 우선순위 ASC, created_at DESC, id DESC
 * 모집 상태와 등록 시각은 관련도(tier, bonus)가 완전히 같을 때만 순서를 정한다.
 *
 * tier (제목 일치 등급, 검색어 형태는 SearchKeyword.phraseForms)
 *   5 완전 일치: 공백 제거 제목 또는 앞머리 제거 제목이 검색어와 같다
 *   4 접두 일치: 앞머리 제거 제목이 검색어로 시작한다
 *   3 구문 일치: 구분자를 공백으로 바꾼 제목에 검색어(공백형)가 있다
 *   2 공백 무시 구문 일치: 공백 제거 제목에 검색어(붙인 형태)가 있다
 *   1 모든 토큰이 제목에 있다 (구문 모드 제외)
 *   검색어 양끝이 영문·숫자이면 그쪽에 영문 경계를 요구한다. 영문·숫자 1~2자 검색어는 영문 단어 경계로만 본다.
 * bonus (앞의 MAX_TOKENS개 토큰)
 *   min(60, 30 × 정책 keywords 항목 접두가 맞는 토큰 수) + 40(훈련구분 일치) + 20(훈련 NCS 분류 세그먼트 일치)
 * 모집 상태 우선순위 (기존 도메인 판정 재사용)
 *   0 OPEN·ALWAYS_OPEN, 훈련은 기존 검색의 OPEN 규칙(교육 시작일이 오늘 이후)도 0 / 1 UPCOMING / 2 UNKNOWN / 3 CLOSED
 *
 * 목록 질의에만 쓰고 COUNT 질의에는 넣지 않는다. PostgreSQL 전용이다 (생성 컬럼·정규식 사용).
 */
final class SearchRelevance {

    /** 구분자를 공백으로 바꾼 제목(t_sp)에서 공백으로 바꾸는 문자 */
    private static final String SPACED_SEPARATORS = "[/,·・‧ᆞ|()\\[\\]{}「」『』<>\"'`~!?:;&]";
    /** 앞머리 제거 제목(t_core): 공백 제거 제목 앞의 [..]·(..)·【..】·★☆·연도 표기를 뗀다 */
    private static final String CORE_PREFIX = "^((\\[[^]]*\\]|\\([^)]*\\)|【[^】]*】|[★☆]|[0-9]{2,4}년)+)";
    private static final String WORD_START = "(^|[^a-z0-9])";
    private static final String WORD_END = "([^a-z0-9]|$)";

    private SearchRelevance() {
    }

    static void orderBy(Root<Opportunity> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder,
                        SearchKeyword keyword, Map<String, String> policyKeywords,
                        Map<String, String> trainingTargets, LocalDate today) {
        List<Order> orders = new ArrayList<>();
        orders.add(criteriaBuilder.desc(tier(root, criteriaBuilder, keyword)));
        // 보너스 신호가 없으면(토큰이 모두 범용어 등) 모든 행이 0이라 순서에 영향이 없다. 상수 0을 ORDER BY에 두면
        // PostgreSQL이 "0번째 컬럼" 위치 참조로 해석해 실패하므로 정렬 키에서 뺀다.
        List<Expression<Integer>> bonusParts = bonusParts(root, query, criteriaBuilder, keyword,
                policyKeywords, trainingTargets);
        if (!bonusParts.isEmpty()) {
            orders.add(criteriaBuilder.desc(sum(criteriaBuilder, bonusParts)));
        }
        orders.add(criteriaBuilder.asc(statusPriority(root, query, criteriaBuilder, today)));
        orders.add(criteriaBuilder.desc(root.get("createdAt")));
        orders.add(criteriaBuilder.desc(root.get("id")));
        query.orderBy(orders);
    }

    static Expression<Integer> tier(Root<Opportunity> root, CriteriaBuilder criteriaBuilder, SearchKeyword keyword) {
        Expression<String> normalized = root.get("searchTitleNorm");
        Expression<String> stripped = root.get("searchTitleStrip");
        Expression<String> spaced = replaceAll(criteriaBuilder,
                replaceAll(criteriaBuilder, replaceAll(criteriaBuilder, normalized, SPACED_SEPARATORS, " "), "-", ""),
                "\\s+", " ");
        Expression<String> core = replaceAll(criteriaBuilder, stripped, CORE_PREFIX, "");

        List<Predicate> exact = new ArrayList<>();
        List<Predicate> prefix = new ArrayList<>();
        List<Predicate> phrase = new ArrayList<>();
        List<Predicate> phraseStripped = new ArrayList<>();
        for (SearchKeyword.PhraseForm form : keyword.phraseForms()) {
            String joined = form.joined();
            String escaped = SearchKeyword.regexEscape(joined);
            exact.add(criteriaBuilder.or(criteriaBuilder.equal(stripped, joined), criteriaBuilder.equal(core, joined)));
            if (SearchKeyword.Token.shortLatin(joined)) {
                prefix.add(matches(criteriaBuilder, core, "^" + escaped + WORD_END));
                phrase.add(matches(criteriaBuilder, normalized, WORD_START + escaped + WORD_END));
                continue;
            }
            String left = form.leftBoundary();
            String right = form.rightBoundary();
            boolean bounded = !left.isEmpty() || !right.isEmpty();
            prefix.add(right.isEmpty()
                    ? criteriaBuilder.like(core, SearchKeyword.escapeLike(joined) + "%", '\\')
                    : matches(criteriaBuilder, core, "^" + escaped + right));
            phrase.add(bounded
                    ? matches(criteriaBuilder, spaced, left + SearchKeyword.regexEscape(form.spaced()) + right)
                    : criteriaBuilder.like(spaced, SearchKeyword.contains(form.spaced()), '\\'));
            phraseStripped.add(bounded
                    ? matches(criteriaBuilder, stripped, left + escaped + right)
                    : criteriaBuilder.like(stripped, SearchKeyword.contains(joined), '\\'));
        }
        Predicate allTokens = keyword.phraseMode()
                ? criteriaBuilder.disjunction()
                : criteriaBuilder.and(keyword.tokens().stream()
                        .map(token -> criteriaBuilder.or(token.variants().stream()
                                .map(variant -> OpportunitySearchService.titleMatches(root, criteriaBuilder, variant))
                                .toArray(Predicate[]::new)))
                        .toArray(Predicate[]::new));
        return criteriaBuilder.<Integer>selectCase()
                .when(anyOf(criteriaBuilder, exact), 5)
                .when(anyOf(criteriaBuilder, prefix), 4)
                .when(anyOf(criteriaBuilder, phrase), 3)
                .when(anyOf(criteriaBuilder, phraseStripped), 2)
                .when(allTokens, 1)
                .otherwise(0);
    }

    static Expression<Integer> bonus(Root<Opportunity> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder,
                                     SearchKeyword keyword, Map<String, String> policyKeywords,
                                     Map<String, String> trainingTargets) {
        List<Expression<Integer>> parts = bonusParts(root, query, criteriaBuilder, keyword,
                policyKeywords, trainingTargets);
        return parts.isEmpty() ? criteriaBuilder.literal(0) : sum(criteriaBuilder, parts);
    }

    /** bonus를 이루는 항목. 검색어에 해당 신호가 없으면 그 항목은 없다 (모두 없으면 빈 목록 = bonus 0). */
    private static List<Expression<Integer>> bonusParts(Root<Opportunity> root, CriteriaQuery<?> query,
                                                        CriteriaBuilder criteriaBuilder, SearchKeyword keyword,
                                                        Map<String, String> policyKeywords,
                                                        Map<String, String> trainingTargets) {
        Expression<Integer> keywordTokens = null;
        Set<String> targets = new LinkedHashSet<>();
        List<Predicate> ncsSegments = new ArrayList<>();
        for (SearchKeyword.Token token : keyword.bonusTokens()) {
            List<String> keywords = OpportunitySearchService.matchingValues(policyKeywords,
                    token.policyKeywordVariants(), OpportunitySearchService.POLICY_KEYWORD_PREFIX);
            if (!keywords.isEmpty()) {
                Expression<Integer> matched = criteriaBuilder.<Integer>selectCase()
                        .when(OpportunitySearchService.detailValueIn(root, query, criteriaBuilder,
                                OpportunityType.YOUTH_POLICY, YouthPolicyDetail.class, "keywords", keywords), 1)
                        .otherwise(0);
                keywordTokens = keywordTokens == null ? matched : criteriaBuilder.sum(keywordTokens, matched);
            }
            targets.addAll(OpportunitySearchService.matchingValues(trainingTargets,
                    token.trainingTargetVariants(), OpportunitySearchService.TRAINING_TARGET_CONTAINS));
            // NCS 비교 표기는 정책 keywords와 같은 조건(2자 이상, 범용어 제외)이다
            for (String variant : token.policyKeywordVariants()) {
                ncsSegments.add(criteriaBuilder.isTrue(criteriaBuilder.function(
                        SearchSqlFunctions.NCS_SEGMENT_MATCH, Boolean.class,
                        root.get("subCategory"), root.get("detailCategory"), criteriaBuilder.literal(variant))));
            }
        }

        List<Expression<Integer>> parts = new ArrayList<>();
        if (keywordTokens != null) {
            // min(60, 30 × 맞는 토큰 수)
            parts.add(criteriaBuilder.<Integer>selectCase()
                    .when(criteriaBuilder.ge(keywordTokens, 2), 60)
                    .otherwise(criteriaBuilder.prod(keywordTokens, 30)));
        }
        if (!targets.isEmpty()) {
            parts.add(criteriaBuilder.<Integer>selectCase()
                    .when(OpportunitySearchService.detailValueIn(root, query, criteriaBuilder,
                            OpportunityType.VOCATIONAL_TRAINING, TrainingCourseDetail.class, "trainingTargetName",
                            List.copyOf(targets)), 40)
                    .otherwise(0));
        }
        if (!ncsSegments.isEmpty()) {
            parts.add(criteriaBuilder.<Integer>selectCase()
                    .when(criteriaBuilder.and(
                            criteriaBuilder.equal(root.get("opportunityType"), OpportunityType.VOCATIONAL_TRAINING),
                            criteriaBuilder.or(ncsSegments.toArray(Predicate[]::new))), 20)
                    .otherwise(0));
        }
        return parts;
    }

    private static Expression<Integer> sum(CriteriaBuilder criteriaBuilder, List<Expression<Integer>> parts) {
        Expression<Integer> total = parts.getFirst();
        for (Expression<Integer> part : parts.subList(1, parts.size())) {
            total = criteriaBuilder.sum(total, part);
        }
        return total;
    }

    /** 모집 상태 우선순위. 관련도가 같을 때만 쓴다. 상태 판정은 ApplicationStatusSpecifications를 그대로 쓴다. */
    static Expression<Integer> statusPriority(Root<Opportunity> root, CriteriaQuery<?> query,
                                              CriteriaBuilder criteriaBuilder, LocalDate today) {
        // 훈련은 신청기간이 없어 도메인 상태가 UNKNOWN이다. 기존 검색의 OPEN 규칙(개강 예정)을 같은 묶음에 넣는다.
        Subquery<Long> startsLater = query.subquery(Long.class);
        Root<TrainingCourseDetail> detail = startsLater.from(TrainingCourseDetail.class);
        startsLater.select(detail.get("id")).where(
                criteriaBuilder.equal(detail.get("version"), root.get("currentVersion")),
                criteriaBuilder.greaterThanOrEqualTo(detail.get("trainingStartDate"), today));
        Predicate upcomingCourse = criteriaBuilder.and(
                criteriaBuilder.equal(root.get("opportunityType"), OpportunityType.VOCATIONAL_TRAINING),
                criteriaBuilder.exists(startsLater));
        // 도메인 상태 판정(OPEN > ALWAYS_OPEN > UPCOMING > CLOSED > UNKNOWN)을 신청기간 한 번 조회로 계산한다.
        // 신청기간마다 해당하는 가장 앞선 상태의 우선순위를 매기고 가장 작은 값을 고른다 (해당 없음은 NULL).
        // 상태별 EXISTS(ApplicationStatusSpecifications.hasStatus)를 나열하면 부정 조건까지 10개의 하위 질의가
        // 생기고 각각 신청기간 전체를 읽는다. 판정 조건은 같은 클래스의 신청기간 단위 판정을 그대로 쓴다.
        Subquery<Integer> windowPriority = query.subquery(Integer.class);
        Root<ApplicationWindow> window = windowPriority.from(ApplicationWindow.class);
        Expression<Integer> priority = criteriaBuilder.<Integer>selectCase()
                .when(ApplicationStatusSpecifications.open(window, criteriaBuilder, today), 0)
                .when(ApplicationStatusSpecifications.always(window, criteriaBuilder), 0)
                .when(ApplicationStatusSpecifications.upcoming(window, criteriaBuilder, today), 1)
                .when(ApplicationStatusSpecifications.closed(window, criteriaBuilder, today), 3)
                .otherwise(criteriaBuilder.nullLiteral(Integer.class));
        windowPriority.select(criteriaBuilder.min(priority)).where(
                criteriaBuilder.equal(window.get("version"), root.get("currentVersion")),
                criteriaBuilder.equal(window.get("validationStatus"), ValidationStatus.VALID));
        return criteriaBuilder.<Integer>selectCase()
                .when(upcomingCourse, 0)
                .otherwise(criteriaBuilder.coalesce(windowPriority, 2));
    }

    private static Expression<String> replaceAll(CriteriaBuilder criteriaBuilder, Expression<String> value,
                                                 String regex, String replacement) {
        return criteriaBuilder.function(SearchSqlFunctions.REGEX_REPLACE_ALL, String.class,
                value, criteriaBuilder.literal(regex), criteriaBuilder.literal(replacement));
    }

    private static Predicate matches(CriteriaBuilder criteriaBuilder, Expression<String> value, String regex) {
        return criteriaBuilder.isTrue(criteriaBuilder.function(
                SearchSqlFunctions.REGEX_MATCH, Boolean.class, value, criteriaBuilder.literal(regex)));
    }

    private static Predicate anyOf(CriteriaBuilder criteriaBuilder, List<Predicate> predicates) {
        return predicates.isEmpty()
                ? criteriaBuilder.disjunction()
                : criteriaBuilder.or(predicates.toArray(Predicate[]::new));
    }
}
