package com.team3.backend.collection.service;

import com.team3.backend.collection.domain.CollectionSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@Component
public class RawRecordFingerprint {
    public String externalId(CollectionSource source, JsonNode payload) {
        String id = requiredText(payload, source == CollectionSource.YOUTH_POLICY ? "plcyNo" : "trprId");
        if (source == CollectionSource.EMPLOYMENT24) {
            id = JsonNodeFactory.instance.arrayNode().add(id).add(requiredText(payload, "trprDegr")).toString();
        }
        if (id.length() > 255) {
            throw new IllegalArgumentException("외부 식별자가 저장 가능한 길이를 초과했습니다.");
        }
        return id;
    }

    public String hash(JsonNode payload) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(sorted(payload).toString().getBytes(StandardCharsets.UTF_8));
            return "sha256-json-v1:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isTextual() || value.asString().isBlank()) {
            throw new IllegalArgumentException("외부 식별 필드가 누락되었거나 문자열이 아닙니다.");
        }
        return value.asString();
    }

    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            value.properties().forEach(entry -> fields.put(entry.getKey(), sorted(entry.getValue())));
            var object = JsonNodeFactory.instance.objectNode();
            fields.forEach(object::set);
            return object;
        }
        if (value.isArray()) {
            var array = JsonNodeFactory.instance.arrayNode();
            value.forEach(element -> array.add(sorted(element)));
            return array;
        }
        return value;
    }
}
