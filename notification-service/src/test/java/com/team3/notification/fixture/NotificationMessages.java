package com.team3.notification.fixture;

import com.team3.notification.message.NotificationMessageDecoder;
import com.team3.notification.message.NotificationRequestedMessage;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared wire example, with individual test overrides. No runtime dependency between projects. */
public final class NotificationMessages {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private NotificationMessages() { }

    public static String json(Map<String, ?> overrides) {
        try {
            String source = new ClassPathResource("contracts/notification-requested-v1.json")
                    .getContentAsString(StandardCharsets.UTF_8);
            Map<String, Object> fields = new LinkedHashMap<>(MAPPER.readValue(source, Map.class));
            fields.putAll(overrides);
            return MAPPER.writeValueAsString(fields);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static NotificationRequestedMessage message(Map<String, ?> overrides) {
        return new NotificationMessageDecoder(MAPPER).decode(json(overrides));
    }
}
