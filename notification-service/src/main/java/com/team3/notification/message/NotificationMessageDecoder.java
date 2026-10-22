package com.team3.notification.message;

import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public class NotificationMessageDecoder {
    private final ObjectMapper mapper;
    private final ObjectReader reader;

    public NotificationMessageDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
        this.reader = mapper.readerFor(NotificationRequestedMessage.class)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public NotificationRequestedMessage decode(String json) {
        var root = mapper.readTree(json);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Message must be a JSON object");
        for (String field : new String[]{"schemaVersion", "userId", "opportunityId", "opportunityVersionId"}) {
            var node = root.get(field);
            if (node == null || !node.isIntegralNumber()) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
        }
        return reader.readValue(json);
    }
}
