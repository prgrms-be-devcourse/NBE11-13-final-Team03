package com.team3.backend.outbox;

import com.team3.backend.outbox.domain.entity.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class UserSessionEndedContractTest {
    @Test
    void matchesSharedControlEnvelopeAndUsesPerMessageDedupKey() throws Exception {
        var mapper = JsonMapper.builder().build();
        var expected = mapper.readTree(new ClassPathResource("contracts/user-session-ended-v1.json")
                .getContentAsString(StandardCharsets.UTF_8));
        var outbox = OutboxEvent.userSessionEnded(7L, "44444444-4444-4444-8444-444444444444",
                Instant.parse("2026-10-28T00:00:00Z"));
        var actual = mapper.readTree(mapper.writeValueAsString(outbox.getPayload()));
        for (var field : new String[]{"messageId", "sourceEventId", "correlationId", "causationId"}) {
            ((tools.jackson.databind.node.ObjectNode) actual).put(field, expected.path(field).asText());
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(outbox.getEventType()).isEqualTo("USER_SESSION_ENDED");
        assertThat(outbox.getPayload()).doesNotContainKeys("expiresAt", "title", "opportunityId");
        // Device sessions are reused across logins, so two logouts of one session must both be publishable.
        assertThat(outbox.getDedupKey()).isEqualTo("USER_SESSION_ENDED:" + outbox.getMessageId());
        var again = OutboxEvent.userSessionEnded(7L, "44444444-4444-4444-8444-444444444444", Instant.parse("2026-10-28T00:00:00Z"));
        assertThat(again.getDedupKey()).isNotEqualTo(outbox.getDedupKey());
    }
}
