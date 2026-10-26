package com.team3.backend.tracking.service;

import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.tracking.config.MatchTrackingProperties;
import com.team3.backend.tracking.domain.entity.MatchCandidateEvent;
import com.team3.backend.tracking.domain.entity.MatchPairState;
import com.team3.backend.tracking.domain.model.MatchObservation;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class MatchNotificationMessageFactory {
    public record Member(MatchCandidateEvent candidate,MatchObservation current) { }

    /** Summary has a real representative policy for v1 wire attribution, not a fabricated policy ID. */
    public OutboxEvent summary(long userId,UUID runId,List<Member> members,MatchTrackingProperties settings,Instant now,Instant expiresAt) {
        if (runId==null || members==null || members.isEmpty() || now==null || expiresAt==null
                || !expiresAt.isAfter(now) || settings.summaryLink().isEmpty())
            throw new IllegalArgumentException("Nonempty current summary, explicit expiry and verified link required");
        var seen=new HashSet<Long>(); int eligible=0,possibly=0,maxUnresolved=0,transitions=0;
        for (var member:members) {
            requireOwner(member,userId,null);
            if (!seen.add(member.candidate().getPair().getId())) throw new IllegalArgumentException("Duplicate summary policy");
            var qualification=member.current().qualify(settings.possibly());
            if (member.candidate().getReason()==MatchPairState.Reason.TRANSITION) {
                if (qualification!=MatchObservation.Qualification.ELIGIBLE) throw new IllegalArgumentException("A current eligible transition is required");
                transitions++; eligible++;
            } else if (qualification==MatchObservation.Qualification.ELIGIBLE) eligible++;
            else if (qualification==MatchObservation.Qualification.POSSIBLY) {
                possibly++; maxUnresolved=Math.max(maxUnresolved,Math.addExact(member.current().userInfoMissing(),member.current().checkRequired()));
            } else throw new IllegalArgumentException("Summary member is no longer a candidate");
        }
        var payload=new LinkedHashMap<String,Object>();
        payload.put("reason","FIRST_MATCH"); payload.put("summary",true); payload.put("summaryRunId",runId.toString());
        payload.put("matchCount",members.size()); payload.put("eligibleCount",eligible); payload.put("possiblyCount",possibly);
        payload.put("maxUnresolved",maxUnresolved); payload.put("transitionCount",transitions);
        payload.put("evaluatedOn",members.getFirst().current().evaluatedOn().toString());
        payload.put("membershipStoredIn","notification_match_candidates.outbox_id");
        if (possibly>0) payload.put("possiblyLabel","확인 필요 " + maxUnresolved + "개");
        var first=members.getFirst();
        var title=transitions==0 ? "가능성 있는 정책 " + members.size() + "건"
                : "새로 신청 가능해진 정책 " + transitions + "건 포함 " + members.size() + "건";
        return envelope(userId,runId,first,"NEW_MATCH",title,
                "새롭게 확인한 정책을 추천 페이지에서 확인해 주세요. 찜하면 마감 알림을 받아요.",
                settings.summaryLink(),now,expiresAt,payload,now);
    }

    public Optional<OutboxEvent> transition(long userId,Member member,MatchTrackingProperties settings,Instant now) {
        requireOwner(member,userId,MatchPairState.Reason.TRANSITION);
        if (member.current().qualify(settings.possibly())!=MatchObservation.Qualification.ELIGIBLE)
            throw new IllegalArgumentException("A current eligible transition is required");
        var occurredAt=member.candidate().getCreatedAt();
        if (occurredAt.isAfter(now)) throw new IllegalArgumentException("Transition cannot occur in the future");
        var expiresAt=occurredAt.plus(MatchOutboxTransaction.TRANSITION_VALIDITY);
        if (!now.isBefore(expiresAt)) return Optional.empty();
        var payload=new LinkedHashMap<String,Object>(member.candidate().getEvidence());
        payload.put("reason","TRANSITION"); payload.put("occurrence",member.candidate().getOccurrenceNo());
        payload.put("revalidatedVersionId",member.current().versionId()); payload.put("revalidatedProfileVersion",member.current().profileVersion());
        payload.put("revalidatedOn",member.current().evaluatedOn().toString());
        long policy=member.candidate().getPair().getOpportunity().getId();
        return Optional.of(envelope(userId,member.candidate().getEventId(),member,"BECAME_ELIGIBLE",
                "정책의 신청 조건을 충족하게 되었습니다.","현재 신청 조건과 공식 공고를 확인해 주세요.",
                "/opportunities/"+policy,occurredAt,expiresAt,payload,now));
    }

    /** reason=null accepts any summary member: first matches and profile-change transitions. */
    private void requireOwner(Member member,long userId,MatchPairState.Reason reason) {
        var candidate=Objects.requireNonNull(member.candidate()); Objects.requireNonNull(member.current());
        boolean accepted=reason==null ? MatchCandidateEvent.summarized(candidate.getReason(),candidate.getEvidence()) : candidate.getReason()==reason;
        if (!Objects.equals(candidate.getPair().getUser().getId(),userId) || !accepted
                || !"PENDING".equals(candidate.getStatus())) throw new IllegalArgumentException("Owned pending candidate required");
    }
    private OutboxEvent envelope(long userId,UUID source,Member member,String type,String title,String content,String link,
            Instant occurredAt,Instant expiresAt,Map<String,Object> payload,Instant now) {
        UUID message=UUID.randomUUID(); long policy=member.candidate().getPair().getOpportunity().getId();
        var body=new LinkedHashMap<String,Object>();
        body.put("schemaVersion",1); body.put("messageId",message.toString()); body.put("eventType","NOTIFICATION_REQUESTED");
        body.put("sourceEventId",source.toString()); body.put("correlationId",source.toString()); body.put("causationId",source.toString());
        body.put("userId",userId); body.put("opportunityId",policy); body.put("opportunityVersionId",member.current().versionId());
        body.put("type",type); body.put("channels",List.of("IN_APP","PUSH")); body.put("title",title); body.put("content",content);
        body.put("link",link); body.put("occurredAt",occurredAt.toString()); body.put("expiresAt",expiresAt.toString()); body.put("payload",payload);
        // Outbox creation time is independent of the immutable occurrence/expiry of a transition.
        return OutboxEvent.notification(message,source,userId,policy,type,body,now);
    }
}
