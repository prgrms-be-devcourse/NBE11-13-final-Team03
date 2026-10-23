package com.team3.dataservice.collection.dto.external;

import com.team3.dataservice.collection.dto.response.SyncPageFailureResponse;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** completed는 마지막 페이지까지 순회했는지 나타내며, 페이지 실패 여부와는 구분한다. */
public record YouthPolicyCollectionResult(
        List<JsonNode> records,
        List<SyncPageFailureResponse> pageFailures,
        boolean completed
) {
    public YouthPolicyCollectionResult {
        records = List.copyOf(records);
        pageFailures = List.copyOf(pageFailures);
    }
}
