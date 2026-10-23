package com.team3.backend.opportunity.conditionset;

import com.team3.backend.opportunity.domain.repository.ConditionSetRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityConditionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRegionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 백필의 청크 실패 격리를 DB 없이 검증한다. 청크 하나가 실패해도 다음 청크를 계속 처리하고,
 * 실패한 청크는 롤백되어 다음 실행이 다시 처리한다.
 */
class ConditionSetBackfillChunkTest {

    private OpportunityVersionRepository versionRepository;
    private ConditionSetRepository conditionSetRepository;
    private PlatformTransactionManager transactionManager;
    private ConditionSetBackfill backfill;

    @BeforeEach
    void setUp() {
        versionRepository = mock(OpportunityVersionRepository.class);
        conditionSetRepository = mock(ConditionSetRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(conditionSetRepository.resolveId(anyString())).thenReturn(10L);
        backfill = new ConditionSetBackfill(versionRepository, mock(OpportunityConditionRepository.class),
                mock(OpportunityRegionRepository.class), conditionSetRepository, transactionManager);
        ReflectionTestUtils.setField(backfill, "chunkSize", 1);
    }

    @Test
    void 청크_하나가_실패해도_다음_청크를_처리하고_실패한_청크는_롤백한다() {
        when(versionRepository.findIdsWithoutConditionSet(eq(0L), any())).thenReturn(List.of(1L));
        when(versionRepository.findIdsWithoutConditionSet(eq(1L), any())).thenReturn(List.of(2L));
        when(versionRepository.findIdsWithoutConditionSet(eq(2L), any())).thenReturn(List.of());
        when(versionRepository.assignConditionSet(10L, List.of(1L))).thenThrow(new IllegalStateException("재현용 실패"));
        when(versionRepository.assignConditionSet(10L, List.of(2L))).thenReturn(1);

        assertThat(backfill.run()).isEqualTo(1);

        verify(versionRepository).assignConditionSet(10L, List.of(2L));
        verify(transactionManager, times(1)).rollback(any());
    }

    @Test
    void 실패한_청크는_다음_실행에서_다시_처리한다() {
        when(versionRepository.findIdsWithoutConditionSet(eq(0L), any())).thenReturn(List.of(1L));
        when(versionRepository.findIdsWithoutConditionSet(eq(1L), any())).thenReturn(List.of());
        when(versionRepository.assignConditionSet(10L, List.of(1L)))
                .thenThrow(new IllegalStateException("재현용 실패"))
                .thenReturn(1);

        assertThat(backfill.run()).isZero();
        assertThat(backfill.run()).isEqualTo(1);
    }
}
