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

    static String v2Fixture() throws Exception {
        return new ClassPathResource("contracts/collection-completed-v2.json").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void acceptsExecutionOutcomeAndDurationAndPreservesThePreviouslyStoredV1Fingerprint() throws Exception {
        var message = CollectionCompletedMessage.decode(mapper, v2Fixture());
        assertThat(message.payload().jobId()).isEqualTo(7L);
        assertThat(message.payload().syncType()).isEqualTo("MANUAL_RETRY");
        assertThat(message.payload().status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(message.payload().failedPageCount()).isEqualTo(1);
        assertThat(message.payload().durationMillis()).isEqualTo(420_000L);
        var legacy = CollectionCompletedMessage.decode(mapper, fixture());
        assertThat(legacy.payload().jobId()).isNull();
        assertThat(mapper.writeValueAsString(legacy)).doesNotContain("jobId", "durationMillis", "failedPageCount", "syncType", "status");
        assertThat(new NotificationMessageFingerprint(mapper).fingerprint(legacy))
                .isEqualTo("779e02075e264933da8d52f41988cf90ac694e9d678f1c2cd49d5280cbefca11");
    }

    @Test
    void rejectsIncompleteOrInvalidV2OutcomesAndLossyNumberCoercions() throws Exception {
        var valid = v2Fixture();
        for (var invalid : new String[]{valid.replace("MANUAL_RETRY", "UNKNOWN"),
                valid.replace("PARTIAL_SUCCESS", "RUNNING"), valid.replace("PARTIAL_SUCCESS", "SUCCESS"),
                valid.replace("\"jobId\": 7", "\"jobId\": 0"),
                valid.replace("\"jobId\": 7", "\"jobId\": 9223372036854775808"),
                valid.replace("\"jobId\": 7", "\"jobId\": \"7\""),
                valid.replace("\"jobId\": 7", "\"jobId\": null"),
                valid.replace("\"durationMillis\": 420000", "\"durationMillis\": -1"),
                valid.replace("\"durationMillis\": 420000", "\"durationMillis\": 1.2"),
                valid.replace("\"durationMillis\": 420000", "\"durationMillis\": null"),
                valid.replace("\"durationMillis\"", "\"unknown\""),
                valid.replace("\"failedPageCount\": 1", "\"failedPageCount\": -1"),
                valid.replace("\"failedPageCount\": 1", "\"failedPageCount\": 2147483648"),
                valid.replace("\"syncType\": \"MANUAL_RETRY\",", ""),
                valid.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3")}) {
            assertThatThrownBy(() -> CollectionCompletedMessage.decode(mapper, invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_COLLECTION_COMPLETED_MESSAGE").hasNoCause();
        }
        var hashes = new NotificationMessageFingerprint(mapper);
        assertThat(hashes.fingerprint(CollectionCompletedMessage.decode(mapper, valid)))
                .isNotEqualTo(hashes.fingerprint(CollectionCompletedMessage.decode(mapper, valid.replace("420000", "420001"))));
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
