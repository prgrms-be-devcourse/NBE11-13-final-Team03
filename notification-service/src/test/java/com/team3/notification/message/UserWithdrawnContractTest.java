package com.team3.notification.message;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class UserWithdrawnContractTest {
    @Test
    void decodesSeparateControlContractWithoutNotificationFieldsAndIgnoresFutureOptionals() throws Exception {
        String json = new ClassPathResource("contracts/user-withdrawn-v1.json").getContentAsString(StandardCharsets.UTF_8);
        var mapper = JsonMapper.builder().build();
        var decoder = new NotificationMessageDecoder(mapper);
        var message = decoder.decodeWithdrawal(json);
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            assertThat(validation.getValidator().validate(message)).isEmpty();
        }
        assertThat(message.userId()).isEqualTo(7L);
        assertThat(decoder.isWithdrawal(json)).isTrue();
        var changed = new LinkedHashMap<>(mapper.readValue(json, Map.class));
        changed.put("futureOptional", true);
        var withOptional = decoder.decodeWithdrawal(mapper.writeValueAsString(changed));
        assertThat(withOptional).isEqualTo(message);
        var fingerprints = new NotificationMessageFingerprint(mapper);
        assertThat(fingerprints.fingerprint(withOptional)).isEqualTo(fingerprints.fingerprint(message));
        assertThatThrownBy(() -> decoder.decodeWithdrawal(json + " {}" )).isInstanceOf(RuntimeException.class);
        changed.put("userId", "7");
        assertThatThrownBy(() -> decoder.decodeWithdrawal(mapper.writeValueAsString(changed))).isInstanceOf(IllegalArgumentException.class);
        changed.put("userId", 7.5);
        assertThatThrownBy(() -> decoder.decodeWithdrawal(mapper.writeValueAsString(changed))).isInstanceOf(IllegalArgumentException.class);
    }
}
