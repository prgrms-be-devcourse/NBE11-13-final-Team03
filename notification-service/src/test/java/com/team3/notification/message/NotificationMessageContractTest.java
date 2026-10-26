package com.team3.notification.message;

import com.team3.notification.fixture.NotificationMessages;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NotificationMessageContractTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final NotificationMessageValidator validator = new NotificationMessageValidator(VALIDATORS.getValidator());
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final NotificationMessageDecoder decoder = new NotificationMessageDecoder(mapper);
    private final NotificationMessageFingerprint fingerprints = new NotificationMessageFingerprint(mapper);

    @AfterAll
    static void closeValidators() { VALIDATORS.close(); }

    @Test
    void readsApplicationWireContractWithoutApplicationClasses() {
        var message = NotificationMessages.message(Map.of());
        assertThatCode(() -> validator.validate(message)).doesNotThrowAnyException();
        assertThat(message.schemaVersion()).isEqualTo(1);
        assertThat(message.userId()).isEqualTo(7L);
        assertThat(message.type()).isEqualTo(NotificationType.DEADLINE_SOON);
        assertThat(message.channels()).containsExactly(NotificationChannel.IN_APP, NotificationChannel.PUSH);
        assertThat(message.expiresAt()).isEqualTo(Instant.parse("2026-10-26T15:00:00Z"));
        assertThat(message.payload()).containsEntry("threshold", "D-3");
    }

    @Test
    void readsDeadlineExtensionContractWithoutApplicationClasses() throws Exception {
        var resource = new org.springframework.core.io.ClassPathResource("contracts/notification-deadline-extended-v1.json");
        var message = decoder.decode(resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThatCode(() -> validator.validate(message)).doesNotThrowAnyException();
        assertThat(message.type()).isEqualTo(NotificationType.DEADLINE_EXTENDED);
        assertThat(message.channels()).containsExactly(NotificationChannel.IN_APP, NotificationChannel.PUSH);
        assertThat(message.payload()).containsEntry("previousApplicationEndDate", "2026-10-25")
                .containsEntry("applicationEndDate", "2026-10-26");
    }

    @Test
    void readsProvisionalEligibilityContractAndAllThreeSupportedTypes() throws Exception {
        var json = new org.springframework.core.io.ClassPathResource("contracts/notification-eligibility-v1.json")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var message = decoder.decode(json);
        assertThatCode(() -> validator.validate(message)).doesNotThrowAnyException();
        assertThat(message.type()).isEqualTo(NotificationType.BECAME_ELIGIBLE);
        assertThat(message.payload()).containsEntry("mappingPolicyVersion", "development-provisional-v1");
        for (String type : List.of("ELIGIBILITY_DOWNGRADED", "ELIGIBILITY_REVIEW_REQUIRED")) {
            var inAppOnly = decoder.decode(json.replace("BECAME_ELIGIBLE", type)
                    .replace("[\"IN_APP\", \"PUSH\"]", "[\"IN_APP\"]"));
            assertThatCode(() -> validator.validate(inAppOnly)).doesNotThrowAnyException();
            assertThat(inAppOnly.type().name()).isEqualTo(type);
            assertThat(inAppOnly.channels()).containsExactly(NotificationChannel.IN_APP);
        }
    }

    @Test
    void readsNewMatchSummaryContractWithoutApplicationClasses() throws Exception {
        var message = decoder.decode(newMatchJson(Map.of()));
        assertThatCode(() -> validator.validate(message)).doesNotThrowAnyException();
        assertThat(message.type()).isEqualTo(NotificationType.NEW_MATCH);
        assertThat(message.channels()).containsExactly(NotificationChannel.IN_APP, NotificationChannel.PUSH);
        assertThat(message.link()).isEqualTo("/test-recommendations");
        assertThat(message.payload()).containsEntry("matchCount", 3).containsEntry("eligibleCount", 2)
                .containsEntry("possiblyCount", 1).containsEntry("possiblyLabel", "확인 필요 1개");
        assertThat(message.sourceEventId().toString()).isEqualTo(message.payload().get("summaryRunId"));
    }

    @Test
    void newMatchRejectsMalformedRunAndInconsistentCountsBeforePersistence() throws Exception {
        var valid = decoder.decode(newMatchJson(Map.of()));
        for (Map<String, Object> changes : List.<Map<String, Object>>of(
                Map.of("reason", "TRANSITION"), Map.of("summary", false),
                Map.of("summaryRunId", UUID.randomUUID().toString()), Map.of("summaryRunId", "invalid"),
                Map.of("evaluatedOn", "invalid"), Map.of("matchCount", 0),
                Map.of("matchCount", 4), Map.of("eligibleCount", -1),
                Map.of("possiblyCount", "1"), Map.of("maxUnresolved", 1.5),
                Map.of("possiblyLabel", "확인 필요 2개"))) {
            var payload = new LinkedHashMap<>(valid.payload());
            payload.putAll(changes);
            var invalid = decoder.decode(newMatchJson(Map.of("payload", payload)));
            assertThatThrownBy(() -> validator.validate(invalid))
                    .as("invalid NEW_MATCH payload: %s", changes).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void newMatchFingerprintChangesWhenMembershipCountChanges() throws Exception {
        var original = decoder.decode(newMatchJson(Map.of()));
        var payload = new LinkedHashMap<>(original.payload());
        payload.put("matchCount", 4); payload.put("eligibleCount", 3);
        var changed = decoder.decode(newMatchJson(Map.of("payload", payload)));
        validator.validate(changed);
        assertThat(fingerprints.fingerprint(changed)).isNotEqualTo(fingerprints.fingerprint(original));
    }

    @SuppressWarnings("unchecked")
    private String newMatchJson(Map<String, ?> overrides) throws Exception {
        var resource = new ClassPathResource("contracts/notification-new-match-v1.json");
        Map<String, Object> fields = mapper.readValue(resource.getContentAsString(StandardCharsets.UTF_8), Map.class);
        fields.putAll(overrides);
        return mapper.writeValueAsString(fields);
    }

    @Test
    void rejectsInvalidAndUnsupportedEnvelopesBeforePersistence() {
        var missing = new HashMap<String, Object>();
        missing.put("messageId", null);
        var cases = List.<Map<String, ?>>of(missing,
                Map.of("schemaVersion", 2), Map.of("schemaVersion", "1"), Map.of("userId", 7.5),
                Map.of("userId", 0), Map.of("messageId", "not-a-uuid"),
                Map.of("eventType", "USER_WITHDRAWN"), Map.of("type", "POLICY_CHANGED"),
                Map.of("channels", List.of()), Map.of("channels", List.of("EMAIL")),
                Map.of("title", " "), Map.of("title", "x".repeat(501)),
                Map.of("expiresAt", "2026-10-23T00:00:00Z"));
        for (var overrides : cases) {
            assertThatThrownBy(() -> validator.validate(decoder.decode(NotificationMessages.json(overrides))))
                    .as("invalid overrides: %s", overrides.keySet()).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> decoder.decode("[]")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> decoder.decode(NotificationMessages.json(Map.of()) + " {}"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void acceptsOnlyInternalAbsolutePathLinks() {
        for (String link : List.of("https://evil.example/x", "//evil.example/x", "relative/path",
                "/%2fevil.example/x", "/x%5cy", "/x%0ay")) {
            assertThatThrownBy(() -> validator.validate(NotificationMessages.message(Map.of("link", link))))
                    .as("link: %s", link).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> validator.validate(NotificationMessages.message(Map.of("link", "/opportunities/10"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNulInTextAndNestedPayloadBecausePostgresCannotStoreIt() {
        for (var override : List.<Map<String, ?>>of(Map.of("title", "a\u0000b"), Map.of("content", "a\u0000b"),
                Map.of("payload", Map.of("threshold", "D-\u00007")),
                Map.of("payload", Map.of("nested", Map.of("k\u0000", "v"))),
                Map.of("payload", Map.of("list", List.of("ok", "x\u0000"))))) {
            assertThatThrownBy(() -> validator.validate(NotificationMessages.message(override)))
                    .as("override: %s", override.keySet()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void fingerprintIgnoresObjectAndChannelOrderAndUnknownOptionalFieldsButNotKnownValues() {
        var original = NotificationMessages.message(Map.of());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("applicationEndDate", "2026-10-26");
        payload.put("threshold", "D-3");
        var reordered = NotificationMessages.message(Map.of("channels", List.of("PUSH", "IN_APP"),
                "payload", payload, "futureOptionalField", "ignored"));
        assertThat(fingerprints.fingerprint(reordered)).isEqualTo(fingerprints.fingerprint(original)).hasSize(64);
        assertThat(fingerprints.fingerprint(NotificationMessages.message(Map.of("title", "다른 내용"))))
                .isNotEqualTo(fingerprints.fingerprint(original));
    }

    @Test
    void optionalPushExpiryPreservesLegacyFingerprintButChangedKnownExpiryConflicts() {
        var original = NotificationMessages.message(Map.of());
        assertThat(original.pushExpiresAt()).isNull();
        assertThat(original.effectivePushExpiresAt()).isEqualTo(original.expiresAt());
        assertThat(fingerprints.fingerprint(original))
                .isEqualTo("bd16d218b2f563bf9a2d8739933e5eff63e87fd4f11b68e96913da265d2c7167");
        var explicitNull = new HashMap<String, Object>(); explicitNull.put("pushExpiresAt", null);
        assertThat(fingerprints.fingerprint(NotificationMessages.message(explicitNull)))
                .isEqualTo(fingerprints.fingerprint(original));
        var shortened = NotificationMessages.message(Map.of("pushExpiresAt", "2026-10-23T15:00:00Z"));
        assertThat(shortened.effectivePushExpiresAt()).isEqualTo(Instant.parse("2026-10-23T15:00:00Z"));
        assertThat(shortened.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(fingerprints.fingerprint(shortened)).isNotEqualTo(fingerprints.fingerprint(original));
    }

    @Test
    void pushExpiryCannotExtendMessageValidityAndAlreadyExpiredPushDoesNotInvalidateInAppContract() {
        var later = NotificationMessages.message(Map.of("pushExpiresAt", "2026-12-01T00:00:00Z"));
        assertThat(later.effectivePushExpiresAt()).isEqualTo(later.expiresAt());
        var expiredPush = NotificationMessages.message(Map.of("pushExpiresAt", "2026-10-22T00:00:00Z"));
        assertThatCode(() -> validator.validate(expiredPush)).doesNotThrowAnyException();
        assertThat(expiredPush.effectivePushExpiresAt()).isBefore(expiredPush.occurredAt());
    }

    @Test
    void readsPushExpiryExampleAndRejectsMalformedOptionalTimestamp() throws Exception {
        var resource = new org.springframework.core.io.ClassPathResource("contracts/notification-requested-push-expiry-v1.json");
        var message = decoder.decode(resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThatCode(() -> validator.validate(message)).doesNotThrowAnyException();
        assertThat(message.pushExpiresAt()).isEqualTo(Instant.parse("2026-10-23T15:00:00Z"));
        for (Object invalid : List.of(123, true, "not-a-timestamp")) {
            assertThatThrownBy(() -> decoder.decode(NotificationMessages.json(Map.of("pushExpiresAt", invalid))))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
