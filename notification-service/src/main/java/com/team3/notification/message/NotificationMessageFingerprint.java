package com.team3.notification.message;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Stable across JSON object field order and requested-channel order, excluding unknown optional fields. */
@Component
public class NotificationMessageFingerprint {
    private final ObjectMapper mapper;

    public NotificationMessageFingerprint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String fingerprint(NotificationRequestedMessage message) {
        return fingerprintFields(message);
    }

    public String fingerprint(UserWithdrawnMessage message) {
        return fingerprintFields(message);
    }

    private String fingerprintFields(Object message) {
        Map<?, ?> fields = mapper.convertValue(message, Map.class);
        String canonical = mapper.writeValueAsString(canonical(fields));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> fields) {
            var sorted = new TreeMap<String, Object>();
            fields.forEach((key, item) -> sorted.put((String) key, canonical(item)));
            return sorted;
        }
        if (value instanceof Collection<?> items) return items.stream().map(this::canonical).toList();
        return value;
    }
}
