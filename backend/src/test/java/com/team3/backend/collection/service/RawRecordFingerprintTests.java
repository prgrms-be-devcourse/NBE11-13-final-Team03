package com.team3.backend.collection.service;

import com.team3.backend.collection.domain.CollectionSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class RawRecordFingerprintTests {
    private final RawRecordFingerprint fingerprint = new RawRecordFingerprint();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void ignoresNestedObjectKeyOrderButPreservesArrayOrderAndValueTypes() {
        var original = mapper.readTree("""
                {"b":{"z":null,"a":[1,"1"]},"a":true}
                """);
        var reordered = mapper.readTree("""
                {"a":true,"b":{"a":[1,"1"],"z":null}}
                """);
        assertEquals(fingerprint.hash(original), fingerprint.hash(reordered));
        assertNotEquals(fingerprint.hash(original), fingerprint.hash(mapper.readTree("""
                {"b":{"z":null,"a":["1",1]},"a":true}
                """)));
        assertTrue(fingerprint.hash(original).matches("sha256-json-v1:[0-9a-f]{64}"));
    }

    @Test
    void usesPolicyNumberAndKeepsCourseRoundsDistinctWithoutDelimiterCollisions() {
        assertEquals("p1", fingerprint.externalId(CollectionSource.YOUTH_POLICY,
                mapper.readTree("{\"plcyNo\":\"p1\"}")));
        var first = mapper.readTree("{\"trprId\":\"a:b\",\"trprDegr\":\"c\"}");
        var second = mapper.readTree("{\"trprId\":\"a\",\"trprDegr\":\"b:c\"}");
        assertNotEquals(fingerprint.externalId(CollectionSource.EMPLOYMENT24, first),
                fingerprint.externalId(CollectionSource.EMPLOYMENT24, second));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.externalId(CollectionSource.EMPLOYMENT24,
                mapper.readTree("{\"trprId\":\"a\"}")));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.externalId(CollectionSource.YOUTH_POLICY,
                mapper.readTree("{\"plcyNo\":123}")));
    }
}
