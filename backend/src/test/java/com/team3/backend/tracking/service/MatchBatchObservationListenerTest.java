package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.event.EligibilityResultsRefreshed;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchBatchObservationListenerTest {
    private final MatchCandidatePage pages=mock(MatchCandidatePage.class);
    private final MatchBatchObservationListener listener=new MatchBatchObservationListener(pages);
    @Test void scansAllPagesWithNoTopNAndRestartsFromZeroNextRefresh() {
        when(pages.scanUser(7,0,100)).thenReturn(new MatchCandidatePage.Run(100,true,100,100,0));
        when(pages.scanUser(7,100,100)).thenReturn(new MatchCandidatePage.Run(105,false,5,5,0));
        listener.refreshed(new EligibilityResultsRefreshed(7)); listener.refreshed(new EligibilityResultsRefreshed(7));
        verify(pages,times(2)).scanUser(7,0,100); verify(pages,times(2)).scanUser(7,100,100);
    }
    @Test void pageFailureDoesNotEscapeIntoJudgementBatch() {
        when(pages.scanUser(7,0,100)).thenThrow(new IllegalStateException("test"));
        assertThatCode(()->listener.refreshed(new EligibilityResultsRefreshed(7))).doesNotThrowAnyException();
    }
    @Test void individualPolicyFailuresDoNotSkipLaterPages() {
        when(pages.scanUser(7,0,100)).thenReturn(new MatchCandidatePage.Run(100,true,100,0,1));
        when(pages.scanUser(7,100,100)).thenReturn(new MatchCandidatePage.Run(101,false,1,1,0));
        listener.refreshed(new EligibilityResultsRefreshed(7)); verify(pages).scanUser(7,100,100);
    }
    @Test void invalidCursorCannotCreateAnInfiniteLoop() {
        when(pages.scanUser(7,0,100)).thenReturn(new MatchCandidatePage.Run(0,true,100,0,0));
        assertThatCode(()->listener.refreshed(new EligibilityResultsRefreshed(7))).doesNotThrowAnyException();
        verify(pages,times(1)).scanUser(7,0,100);
    }
}
