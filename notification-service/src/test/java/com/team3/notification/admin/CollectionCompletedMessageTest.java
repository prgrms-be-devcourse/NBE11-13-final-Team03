package com.team3.notification.admin;

import com.team3.notification.message.NotificationMessageFingerprint;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class CollectionCompletedMessageTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    static String fixture() throws Exception {
        return new ClassPathResource("contracts/collection-completed-v1.json").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void acceptsTheSharedSixFieldContractWithoutUserOrPolicyIds() throws Exception {
        var message = CollectionCompletedMessage.decode(mapper, fixture());
        assertThat(message.payload().dataSource()).isEqualTo("EMPLOYMENT24");
        assertThat(message.payload().collectedDate().toString()).isEqualTo("2026-10-27");
        assertThat(message.payload().newCount()).isEqualTo(1203);
        assertThat(message.payload().updatedCount()).isEqualTo(88);
        assertThat(message.payload().unchangedCount()).isEqualTo(139201);
        assertThat(message.payload().failedCount()).isEqualTo(4);
        assertThat(fixture()).doesNotContain("userId", "opportunityId");
    }

    @Test
    void rejectsOtherEventsVersionsExtraFieldsAndInvalidNumbersWithoutLeakingBody() throws Exception {
        var valid = fixture();
        for (var invalid : new String[]{"{private-token}", valid.replace("COLLECTION_COMPLETED", "NOTIFICATION_REQUESTED"),
                valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""),
                valid.replace("\"newCount\": 1203", "\"newCount\": -1"),
                valid.replace("\"newCount\": 1203", "\"newCount\": 2147483648"),
                valid.replace("\"newCount\": 1203", "\"newCount\": 1.2"),
                valid.replace("\"newCount\": 1203", "\"newCount\": \"1203\""),
                valid.replace("\"newCount\": 1203", "\"newCount\": null"),
                valid.replace("\"newCount\": 1203", "\"newCount\": 1203, \"private-token\": 1"),
                valid.replace("\"newCount\"", "\"private-token\""),
                valid.replace("EMPLOYMENT24", "UNKNOWN"), valid.replace("2026-10-27\"", "2026-02-30\""),
                valid.replace("4be9056a-0ecf-4aef-8fd9-f9f65fc8c1a1", "1-1-1-1-1")}) {
            assertThatThrownBy(() -> CollectionCompletedMessage.decode(mapper, invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_COLLECTION_COMPLETED_MESSAGE").hasNoCause();
        }
    }

    @Test
    void fingerprintIgnoresJsonFieldOrderButDetectsChangedDataForTheSameId() throws Exception {
        var fingerprints = new NotificationMessageFingerprint(mapper);
        Map<String, Object> fields = mapper.readValue(fixture(), Map.class);
        var reversed = new LinkedHashMap<String, Object>();
        fields.keySet().stream().sorted(java.util.Comparator.reverseOrder()).forEach(key -> reversed.put(key, fields.get(key)));
        var original = CollectionCompletedMessage.decode(mapper, fixture());
        assertThat(fingerprints.fingerprint(original)).isEqualTo(fingerprints.fingerprint(
                CollectionCompletedMessage.decode(mapper, mapper.writeValueAsString(reversed))));
        assertThat(fingerprints.fingerprint(original)).isNotEqualTo(fingerprints.fingerprint(
                CollectionCompletedMessage.decode(mapper, fixture().replace("\"failedCount\": 4", "\"failedCount\": 5"))));
    }
}
