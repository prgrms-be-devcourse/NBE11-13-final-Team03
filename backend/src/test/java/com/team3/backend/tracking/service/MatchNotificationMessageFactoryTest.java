package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.tracking.config.MatchTrackingProperties;
import com.team3.backend.tracking.domain.entity.*;
import com.team3.backend.tracking.domain.model.MatchObservation;
import com.team3.backend.user.domain.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchNotificationMessageFactoryTest {
    private static final Instant NOW=Instant.parse("2026-10-26T00:00:00Z");
    private final MatchNotificationMessageFactory factory=new MatchNotificationMessageFactory();
    private final MatchTrackingProperties settings=new MatchTrackingProperties(true,new MatchTrackingProperties.Possibly(1,true),"/test-recommendations");
    private MatchNotificationMessageFactory.Member member(long policy,MatchPairState.Reason reason,EligibilityStatus status) {
        return member(policy,reason,status,Map.of("before","NOT_ELIGIBLE","after","ELIGIBLE"));
    }
    private MatchNotificationMessageFactory.Member member(long policy,MatchPairState.Reason reason,EligibilityStatus status,Map<String,Object> evidence) {
        var user=mock(User.class); when(user.getId()).thenReturn(7L);
        var opportunity=mock(Opportunity.class); when(opportunity.getId()).thenReturn(policy);
        var pair=mock(MatchPairState.class); when(pair.getId()).thenReturn(policy+100);
        when(pair.getUser()).thenReturn(user); when(pair.getOpportunity()).thenReturn(opportunity);
        var event=mock(MatchCandidateEvent.class); when(event.getPair()).thenReturn(pair); when(event.getReason()).thenReturn(reason);
        when(event.getStatus()).thenReturn("PENDING"); when(event.getEventId()).thenReturn(UUID.randomUUID());
        when(event.getCreatedAt()).thenReturn(NOW.minusSeconds(60)); when(event.getOccurrenceNo()).thenReturn(2L);
        when(event.getEvidence()).thenReturn(evidence);
        var input=new MatchObservation(policy+1000,2,3,1,"4","v1",status,OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.PERSONALIZABLE,ApplicationStatus.ALWAYS_OPEN,false,true,
                status==EligibilityStatus.POSSIBLY_ELIGIBLE?1:0,0,LocalDate.of(2026,10,26));
        return new MatchNotificationMessageFactory.Member(event,input);
    }
    @Test void summaryUsesRealRepresentativeAndCountsEveryMember() {
        var a=member(11,MatchPairState.Reason.FIRST_MATCH,EligibilityStatus.ELIGIBLE);
        var b=member(12,MatchPairState.Reason.FIRST_MATCH,EligibilityStatus.POSSIBLY_ELIGIBLE);
        UUID run=UUID.randomUUID(); var outbox=factory.summary(7,run,List.of(a,b),settings,NOW,NOW.plusSeconds(3600));
        assertThat(outbox.getSourceEventId()).isEqualTo(run); assertThat(outbox.getOpportunityId()).isEqualTo(11);
        assertThat(outbox.getPayload()).containsEntry("opportunityVersionId",1011L).containsEntry("title","가능성 있는 정책 2건")
                .containsEntry("link","/test-recommendations").containsEntry("channels",List.of("IN_APP","PUSH"));
        Map<?,?> evidence=(Map<?,?>)outbox.getPayload().get("payload");
        assertThat(evidence.get("matchCount")).isEqualTo(2); assertThat(evidence.get("eligibleCount")).isEqualTo(1);
        assertThat(evidence.get("possiblyCount")).isEqualTo(1); assertThat(evidence.get("possiblyLabel")).isEqualTo("확인 필요 1개");
    }
    @Test void summaryEnvelopeMatchesSharedNewMatchContract() throws Exception {
        var mapper=JsonMapper.builder().build();
        var sample=mapper.readValue(new ClassPathResource("contracts/notification-new-match-v1.json")
                .getContentAsString(StandardCharsets.UTF_8),Map.class);
        UUID run=UUID.fromString((String)sample.get("sourceEventId"));
        var members=List.of(member(10,MatchPairState.Reason.FIRST_MATCH,EligibilityStatus.ELIGIBLE),
                member(11,MatchPairState.Reason.FIRST_MATCH,EligibilityStatus.ELIGIBLE),
                member(12,MatchPairState.Reason.FIRST_MATCH,EligibilityStatus.POSSIBLY_ELIGIBLE));
        var actual=factory.summary(7,run,members,settings,NOW,NOW.plusSeconds(3600)).getPayload();
        assertThat(actual).containsEntry("schemaVersion",sample.get("schemaVersion"))
                .containsEntry("eventType",sample.get("eventType"))
                .containsEntry("sourceEventId",sample.get("sourceEventId"))
                .containsEntry("type",sample.get("type"))
                .containsEntry("channels",sample.get("channels"))
                .containsEntry("title",sample.get("title"))
                .containsEntry("content",sample.get("content"))
                .containsEntry("link",sample.get("link"));
        assertThat(((Number)actual.get("userId")).longValue())
                .isEqualTo(((Number)sample.get("userId")).longValue());
        assertThat(((Number)actual.get("opportunityId")).longValue())
                .isEqualTo(((Number)sample.get("opportunityId")).longValue());
        Map<?,?> evidence=(Map<?,?>)actual.get("payload");
        Map<?,?> contract=(Map<?,?>)sample.get("payload");
        for(String key:List.of("reason","summary","summaryRunId","matchCount","eligibleCount",
                "possiblyCount","maxUnresolved","transitionCount","membershipStoredIn","possiblyLabel")) {
            assertThat(evidence.get(key)).as("payload.%s",key).isEqualTo(contract.get(key));
        }
    }
    @Test void profileChangeTransitionJoinsSummaryAsEligibleMemberAndOtherTransitionsDoNot() {
        var first=member(11,MatchPairState.Reason.FIRST_MATCH,EligibilityStatus.POSSIBLY_ELIGIBLE);
        var profile=member(12,MatchPairState.Reason.TRANSITION,EligibilityStatus.ELIGIBLE,Map.of("trigger","PROFILE_CHANGED"));
        var outbox=factory.summary(7,UUID.randomUUID(),List.of(first,profile),settings,NOW,NOW.plusSeconds(3600));
        Map<?,?> evidence=(Map<?,?>)outbox.getPayload().get("payload");
        assertThat(outbox.getPayload()).containsEntry("title","새로 신청 가능해진 정책 1건 포함 2건");
        assertThat(evidence.get("matchCount")).isEqualTo(2); assertThat(evidence.get("eligibleCount")).isEqualTo(1);
        assertThat(evidence.get("possiblyCount")).isEqualTo(1); assertThat(evidence.get("transitionCount")).isEqualTo(1);
        var noLongerEligible=member(13,MatchPairState.Reason.TRANSITION,EligibilityStatus.POSSIBLY_ELIGIBLE,Map.of("trigger","PROFILE_CHANGED"));
        assertThatThrownBy(()->factory.summary(7,UUID.randomUUID(),List.of(noLongerEligible),settings,NOW,NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        var policyChange=member(14,MatchPairState.Reason.TRANSITION,EligibilityStatus.ELIGIBLE,Map.of("trigger","POLICY_CHANGED"));
        assertThatThrownBy(()->factory.summary(7,UUID.randomUUID(),List.of(policyChange),settings,NOW,NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void summaryRejectsEmptyDuplicateOrWrongReasonMembers() {
        var a=member(11,MatchPairState.Reason.FIRST_MATCH,EligibilityStatus.ELIGIBLE);
        assertThatThrownBy(()->factory.summary(7,UUID.randomUUID(),List.of(),settings,NOW,NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.summary(7,UUID.randomUUID(),List.of(a,a),settings,NOW,NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        var transition=member(12,MatchPairState.Reason.TRANSITION,EligibilityStatus.ELIGIBLE);
        assertThatThrownBy(()->factory.summary(7,UUID.randomUUID(),List.of(transition),settings,NOW,NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void summaryRequiresExplicitFutureExpiry() {
        var a=member(11,MatchPairState.Reason.FIRST_MATCH,EligibilityStatus.ELIGIBLE);
        assertThatThrownBy(()->factory.summary(7,UUID.randomUUID(),List.of(a),settings,NOW,NOW)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void transitionKeepsOccurrenceAndExpiryWhileOutboxCreationUsesNow() {
        var a=member(11,MatchPairState.Reason.TRANSITION,EligibilityStatus.ELIGIBLE);
        var outbox=factory.transition(7,a,settings,NOW).orElseThrow();
        assertThat(outbox.getSourceEventId()).isEqualTo(a.candidate().getEventId());
        assertThat(outbox.getCreatedAt()).isEqualTo(NOW); assertThat(outbox.getNextRetryAt()).isEqualTo(NOW);
        assertThat(outbox.getPayload()).containsEntry("occurredAt",NOW.minusSeconds(60).toString())
                .containsEntry("expiresAt",NOW.minusSeconds(60).plus(Duration.ofDays(7)).toString())
                .containsEntry("type","BECAME_ELIGIBLE").containsEntry("link","/opportunities/11");
    }
    @Test void transitionAtExpiryDoesNotExtendOrCreateMessage() {
        var a=member(11,MatchPairState.Reason.TRANSITION,EligibilityStatus.ELIGIBLE);
        assertThat(factory.transition(7,a,settings,NOW.minusSeconds(60).plus(Duration.ofDays(7)))).isEmpty();
    }
    @Test void lossUnknownOrOtherUserCannotProduceTransition() {
        var lost=member(11,MatchPairState.Reason.TRANSITION,EligibilityStatus.NOT_ELIGIBLE);
        assertThatThrownBy(()->factory.transition(7,lost,settings,NOW)).isInstanceOf(IllegalArgumentException.class);
        var eligible=member(12,MatchPairState.Reason.TRANSITION,EligibilityStatus.ELIGIBLE);
        assertThatThrownBy(()->factory.transition(8,eligible,settings,NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
