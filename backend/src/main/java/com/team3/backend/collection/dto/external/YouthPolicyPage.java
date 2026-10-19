package com.team3.backend.collection.dto.external;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** 정책별 원본 필드를 유지한다. totalCount는 API에서 제공하지 않으면 null이다. */
public record YouthPolicyPage(Integer totalCount, List<JsonNode> records) {
    public YouthPolicyPage {
        records = List.copyOf(records);
    }
}
