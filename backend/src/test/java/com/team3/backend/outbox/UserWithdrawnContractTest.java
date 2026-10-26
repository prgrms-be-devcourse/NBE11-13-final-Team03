package com.team3.backend.outbox;

import com.team3.backend.outbox.domain.entity.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class UserWithdrawnContractTest {
    @Test
    void matchesSharedControlEnvelopeWithoutNotificationFieldsOrExpiry() throws Exception {
        var mapper = JsonMapper.builder().build();
        var expected = mapper.readTree(new ClassPathResource("contracts/user-withdrawn-v1.json")
                .getContentAsString(StandardCharsets.UTF_8));
        var outbox = OutboxEvent.userWithdrawn(7L, Instant.parse("2026-10-24T00:00:00Z"));
        // Compare the wire JSON, not Java-backed nodes (Long userId=7 vs parsed IntNode=7).
        var actual = mapper.readTree(mapper.writeValueAsString(outbox.getPayload()));
        for (var field : new String[]{"messageId", "sourceEventId", "correlationId", "causationId"}) {
            ((tools.jackson.databind.node.ObjectNode) actual).put(field, expected.path(field).asText());
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(outbox.getDedupKey()).isEqualTo("USER_WITHDRAWN:7");
    }
}
