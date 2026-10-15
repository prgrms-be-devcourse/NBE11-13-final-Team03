package com.team3.backend.collection.dto.external;

import com.team3.backend.collection.dto.response.Employment24PageFailureResponse;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record Employment24CollectionResult(
        List<JsonNode> records,
        List<Employment24PageFailureResponse> pageFailures,
        List<String> incompleteNcsCodes
) {
    public Employment24CollectionResult {
        records = List.copyOf(records);
        pageFailures = List.copyOf(pageFailures);
        incompleteNcsCodes = List.copyOf(incompleteNcsCodes);
    }

    /** 페이지 상한에 막히지 않고 순회를 마쳤는지 나타낸다. 페이지 실패 여부와는 구분한다. */
    public boolean completed() {
        return incompleteNcsCodes.isEmpty();
    }
}
