package com.team3.backend.search.service;

import org.junit.jupiter.api.Test;

import static com.team3.backend.search.dto.OpportunitySearchSort.DEADLINE_PRIORITY;
import static com.team3.backend.search.dto.OpportunitySearchSort.LATEST;
import static com.team3.backend.search.dto.OpportunitySearchSort.OLDEST;
import static com.team3.backend.search.dto.OpportunitySearchSort.RELEVANCE;
import static com.team3.backend.search.dto.OpportunitySearchSort.START_DATE_ASC;
import static org.assertj.core.api.Assertions.assertThat;

class OpportunitySearchSortResolutionTest {

    @Test
    void omittedSortIsRelevanceOnlyWhenRelevanceCanBeComputed() {
        assertThat(OpportunitySearchService.effectiveSort(null, true)).isEqualTo(RELEVANCE);
        assertThat(OpportunitySearchService.effectiveSort(null, false)).isEqualTo(LATEST);
    }

    @Test
    void relevanceWithoutKeywordFallsBackToLatest() {
        assertThat(OpportunitySearchService.effectiveSort(RELEVANCE, true)).isEqualTo(RELEVANCE);
        assertThat(OpportunitySearchService.effectiveSort(RELEVANCE, false)).isEqualTo(LATEST);
    }

    @Test
    void explicitSortsAreRespected() {
        for (var sort : new com.team3.backend.search.dto.OpportunitySearchSort[]{
                LATEST, OLDEST, DEADLINE_PRIORITY, START_DATE_ASC}) {
            assertThat(OpportunitySearchService.effectiveSort(sort, true)).isEqualTo(sort);
            assertThat(OpportunitySearchService.effectiveSort(sort, false)).isEqualTo(sort);
        }
    }
}
