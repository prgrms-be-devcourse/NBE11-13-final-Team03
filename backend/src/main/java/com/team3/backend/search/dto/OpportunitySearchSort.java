package com.team3.backend.search.dto;

/** 검색 목록에서 지원하는 정렬 기준이다. */
public enum OpportunitySearchSort {
    /** 검색어 관련도순. 검색어가 있을 때 정렬을 생략하면 기본값이다 */
    RELEVANCE,
    LATEST,
    OLDEST,
    DEADLINE_PRIORITY,
    START_DATE_ASC
}
